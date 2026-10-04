package com.smsapp.fee;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Line-by-line view of student bills and line-by-line collection (V42).
 *
 * <p>Invoice totals remain the source of truth for what is owed and paid; this service keeps the lines and the
 * payment allocations consistent with them:
 * <ul>
 *   <li>a new bill gets one line per fee line of its structure (or the per-student lines given at admission);</li>
 *   <li>a discount is spread over the lines in proportion to their amounts;</li>
 *   <li>a collection pays chosen lines (fee + fine) with one payment per bill, all sharing a collection id;</li>
 *   <li>payments without allocations (older ones, online ones) are attributed to lines oldest-due-first.</li>
 * </ul>
 */
@Service
public class FeeLineService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InvoiceLineRepository lineRepository;
    private final FeePaymentAllocationRepository allocationRepository;
    private final FeePaymentRepository paymentRepository;
    private final FeeStructureItemRepository itemRepository;
    private final FeeTypeRepository feeTypeRepository;
    private final InvoiceRepository invoiceRepository;
    private final FeePaymentRecorder paymentRecorder;
    private final AuditService auditService;

    public FeeLineService(InvoiceLineRepository lineRepository, FeePaymentAllocationRepository allocationRepository,
                          FeePaymentRepository paymentRepository, FeeStructureItemRepository itemRepository,
                          FeeTypeRepository feeTypeRepository, InvoiceRepository invoiceRepository,
                          FeePaymentRecorder paymentRecorder, AuditService auditService) {
        this.lineRepository = lineRepository;
        this.allocationRepository = allocationRepository;
        this.paymentRepository = paymentRepository;
        this.itemRepository = itemRepository;
        this.feeTypeRepository = feeTypeRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRecorder = paymentRecorder;
        this.auditService = auditService;
    }

    /** A fee line for a new bill: from the structure, or adjusted for one student at admission. */
    public record LineSpec(String label, UUID feeTypeId, String category, LocalDate dueDate, BigDecimal amount) {
    }

    // --- Creating lines -----------------------------------------------------------------------------------------

    /** The structure's fee lines as specs (a structure without lines is one line for its whole amount). */
    public List<LineSpec> specsFor(FeeStructure structure) {
        List<FeeStructureItem> items = itemRepository.findByFeeStructureIdOrderBySequenceOrder(structure.getId());
        if (items.isEmpty()) {
            return List.of(new LineSpec(structure.getName(), null, FeeStructureItemCategory.OTHER,
                    structure.getDueDate(), structure.getAmount()));
        }
        Map<UUID, String> typeNames = new HashMap<>();
        feeTypeRepository.findAllById(items.stream().map(FeeStructureItem::getFeeTypeId).filter(Objects::nonNull).toList())
                .forEach(t -> typeNames.put(t.getId(), t.getName()));
        return items.stream().map(item -> new LineSpec(
                item.getLabel() != null && !item.getLabel().isBlank() ? item.getLabel()
                        : typeNames.getOrDefault(item.getFeeTypeId(), structure.getName()),
                item.getFeeTypeId(), item.getCategory(),
                item.getDueDate() != null ? item.getDueDate() : structure.getDueDate(),
                item.getAmount())).toList();
    }

    /** Saves the lines of a just-created bill. Their amounts must add up to the bill's amount. */
    @Transactional
    public void createLines(Invoice invoice, List<LineSpec> specs) {
        BigDecimal sum = specs.stream().map(LineSpec::amount).reduce(ZERO, BigDecimal::add);
        if (sum.compareTo(invoice.getAmount()) != 0) {
            throw new IllegalStateException("Fee lines (" + sum + ") do not add up to the bill (" + invoice.getAmount() + ")");
        }
        List<InvoiceLine> lines = new ArrayList<>();
        int sequence = 0;
        for (LineSpec spec : specs) {
            InvoiceLine line = new InvoiceLine();
            line.setInvoiceId(invoice.getId());
            line.setLabel(truncate(spec.label(), 150));
            line.setFeeTypeId(spec.feeTypeId());
            line.setCategory(spec.category() == null ? FeeStructureItemCategory.OTHER : spec.category());
            line.setDueDate(spec.dueDate());
            line.setAmount(spec.amount());
            line.setSequenceOrder(sequence++);
            lines.add(line);
        }
        lineRepository.saveAll(lines);
    }

    /** Spreads the bill's discount over its lines in proportion to their amounts (the last line absorbs rounding). */
    @Transactional
    public void distributeDiscount(Invoice invoice) {
        List<InvoiceLine> lines = lineRepository.findByInvoiceIdOrderByDueDateAscSequenceOrderAsc(invoice.getId());
        if (lines.isEmpty()) {
            return;
        }
        BigDecimal total = invoice.getDiscountAmount();
        BigDecimal gross = lines.stream().map(InvoiceLine::getAmount).reduce(ZERO, BigDecimal::add);
        BigDecimal given = ZERO;
        for (int i = 0; i < lines.size(); i++) {
            InvoiceLine line = lines.get(i);
            BigDecimal share = i == lines.size() - 1 || gross.signum() == 0
                    ? total.subtract(given)
                    : line.getAmount().multiply(total).divide(gross, 2, RoundingMode.HALF_UP);
            share = share.max(ZERO).min(line.getAmount());
            line.setDiscountAmount(share);
            given = given.add(share);
        }
        lineRepository.saveAll(lines);
    }

    // --- Reading: the per-line ledger ---------------------------------------------------------------------------

    /** One payment as it touched one line. */
    public record LinePayment(UUID paymentId, String receiptNumber, int index, String method, LocalDate paymentDate,
                              BigDecimal amount, BigDecimal fine, UUID collectionId, String referenceNumber,
                              String notes, boolean reversed) {
    }

    /** A line with what has been paid on it. */
    public record LineState(InvoiceLine line, BigDecimal paid, BigDecimal fine, BigDecimal balance, String status,
                            List<LinePayment> payments) {
    }

    public static final String PAID = "PAID";
    public static final String PARTIAL = "PARTIAL";
    public static final String UNPAID = "UNPAID";

    /** Every line of the given bills with its paid amount, fine, balance, status and payments. */
    @Transactional(readOnly = true)
    public Map<UUID, List<LineState>> ledger(List<Invoice> invoices) {
        Map<UUID, List<LineState>> result = new LinkedHashMap<>();
        if (invoices.isEmpty()) {
            return result;
        }
        List<UUID> invoiceIds = invoices.stream().map(Invoice::getId).toList();
        Map<UUID, List<InvoiceLine>> linesByInvoice = new HashMap<>();
        lineRepository.findByInvoiceIdIn(invoiceIds).forEach(l ->
                linesByInvoice.computeIfAbsent(l.getInvoiceId(), k -> new ArrayList<>()).add(l));
        List<FeePayment> payments = paymentRepository.findByInvoiceIdInOrderByPaidAtAsc(invoiceIds);
        Set<UUID> reversed = new HashSet<>();
        payments.stream().filter(p -> PaymentType.REVERSAL.equals(p.getType()))
                .forEach(p -> reversed.add(p.getReversesPaymentId()));
        Map<UUID, List<FeePaymentAllocation>> allocationsByPayment = new HashMap<>();
        allocationRepository.findByPaymentIdIn(payments.stream().map(FeePayment::getId).toList())
                .forEach(a -> allocationsByPayment.computeIfAbsent(a.getPaymentId(), k -> new ArrayList<>()).add(a));

        for (Invoice invoice : invoices) {
            List<InvoiceLine> lines = new ArrayList<>(linesByInvoice.getOrDefault(invoice.getId(), List.of()));
            lines.sort(Comparator.comparing(InvoiceLine::getDueDate).thenComparingInt(InvoiceLine::getSequenceOrder));
            Map<UUID, BigDecimal> paid = new HashMap<>();
            Map<UUID, BigDecimal> fine = new HashMap<>();
            Map<UUID, List<LinePayment>> linePayments = new HashMap<>();

            for (FeePayment payment : payments) {
                if (!payment.getInvoiceId().equals(invoice.getId()) || !PaymentType.PAYMENT.equals(payment.getType())) {
                    continue;
                }
                boolean isReversed = reversed.contains(payment.getId());
                List<FeePaymentAllocation> allocations = allocationsByPayment.getOrDefault(payment.getId(), List.of());
                if (!allocations.isEmpty()) {
                    int index = 1;
                    for (FeePaymentAllocation a : allocations) {
                        linePayments.computeIfAbsent(a.getInvoiceLineId(), k -> new ArrayList<>())
                                .add(view(payment, index++, a.getAmount(), a.getFineAmount(), isReversed));
                        if (!isReversed) {
                            paid.merge(a.getInvoiceLineId(), a.getAmount(), BigDecimal::add);
                            fine.merge(a.getInvoiceLineId(), a.getFineAmount(), BigDecimal::add);
                        }
                    }
                } else if (!isReversed) {
                    // An older or online payment: pay the lines oldest-due-first.
                    BigDecimal left = payment.getAmount();
                    int index = 1;
                    for (InvoiceLine line : lines) {
                        if (left.signum() <= 0) {
                            break;
                        }
                        BigDecimal open = payable(line).subtract(paid.getOrDefault(line.getId(), ZERO));
                        if (open.signum() <= 0) {
                            continue;
                        }
                        BigDecimal take = open.min(left);
                        paid.merge(line.getId(), take, BigDecimal::add);
                        linePayments.computeIfAbsent(line.getId(), k -> new ArrayList<>())
                                .add(view(payment, index++, take, ZERO, false));
                        left = left.subtract(take);
                    }
                }
            }

            List<LineState> states = new ArrayList<>();
            for (InvoiceLine line : lines) {
                BigDecimal linePaid = paid.getOrDefault(line.getId(), ZERO);
                BigDecimal balance = payable(line).subtract(linePaid).max(ZERO);
                String status = balance.signum() == 0 ? PAID : linePaid.signum() > 0 ? PARTIAL : UNPAID;
                states.add(new LineState(line, linePaid, fine.getOrDefault(line.getId(), ZERO), balance, status,
                        linePayments.getOrDefault(line.getId(), List.of())));
            }
            result.put(invoice.getId(), states);
        }
        return result;
    }

    private static BigDecimal payable(InvoiceLine line) {
        return line.getAmount().subtract(line.getDiscountAmount()).max(ZERO);
    }

    private static LinePayment view(FeePayment p, int index, BigDecimal amount, BigDecimal fine, boolean reversed) {
        return new LinePayment(p.getId(), p.getReceiptNumber(), index, p.getMethod(), p.getPaymentDate(), amount, fine,
                p.getCollectionId(), p.getReferenceNumber(), p.getNotes(), reversed);
    }

    // --- Collecting ---------------------------------------------------------------------------------------------

    /** What to pay on one line. */
    public record LinePaymentRequest(UUID invoiceLineId, BigDecimal amount, BigDecimal fine) {
    }

    public record CollectionOutcome(UUID collectionId, List<FeePayment> payments, BigDecimal total) {
    }

    /**
     * Collects fees for the chosen lines of one student's bills: one payment per bill touched (each with its own
     * receipt number), all sharing a collection id. Fines are added to the bill's late fee. The bills are locked
     * for the whole transaction (in id order, so two collectors can't deadlock) and every amount is checked against
     * the line's balance computed under that lock.
     *
     * @throws ApiException 400 on an invalid method/date/amount, 404 if a line isn't this student's, 409 if a line
     *                      is already paid.
     */
    @Transactional
    public CollectionOutcome collect(UUID studentId, LocalDate paymentDate, String method, String referenceNumber,
                                     String notes, List<LinePaymentRequest> requests, UUID collectedBy) {
        if (!PaymentMethod.isValidManual(method)) {
            throw new ApiException("Not a valid payment mode: " + method, HttpStatus.BAD_REQUEST);
        }
        if (paymentDate == null || paymentDate.isAfter(LocalDate.now())) {
            throw new ApiException("The payment date can't be in the future", HttpStatus.BAD_REQUEST);
        }
        if (requests == null || requests.isEmpty()) {
            throw new ApiException("Select at least one fee to collect", HttpStatus.BAD_REQUEST);
        }
        Set<UUID> seen = new HashSet<>();
        for (LinePaymentRequest r : requests) {
            if (r.invoiceLineId() == null || !seen.add(r.invoiceLineId())) {
                throw new ApiException("Each fee can be listed only once", HttpStatus.BAD_REQUEST);
            }
            BigDecimal amount = nz(r.amount());
            BigDecimal fine = nz(r.fine());
            if (amount.signum() < 0 || fine.signum() < 0) {
                throw new ApiException("Amounts can't be negative", HttpStatus.BAD_REQUEST);
            }
            if (amount.scale() > 2 || fine.scale() > 2) {
                throw new ApiException("Amounts can have at most 2 decimal places", HttpStatus.BAD_REQUEST);
            }
        }

        Map<UUID, InvoiceLine> lines = new HashMap<>();
        lineRepository.findAllById(seen).forEach(l -> lines.put(l.getId(), l));
        if (lines.size() != seen.size()) {
            throw new ApiException("Fee not found", HttpStatus.NOT_FOUND);
        }
        List<UUID> invoiceIds = lines.values().stream().map(InvoiceLine::getInvoiceId).distinct().sorted().toList();
        List<Invoice> invoices = new ArrayList<>();
        for (UUID invoiceId : invoiceIds) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                    .orElseThrow(() -> new ApiException("Fee not found", HttpStatus.NOT_FOUND));
            if (!invoice.getStudentId().equals(studentId)) {
                throw new ApiException("Fee not found", HttpStatus.NOT_FOUND);
            }
            invoices.add(invoice);
        }
        Map<UUID, LineState> states = new HashMap<>();
        ledger(invoices).values().forEach(list -> list.forEach(s -> states.put(s.line().getId(), s)));

        for (LinePaymentRequest r : requests) {
            LineState state = states.get(r.invoiceLineId());
            BigDecimal amount = nz(r.amount());
            if (amount.add(nz(r.fine())).signum() == 0) {
                throw new ApiException("Enter an amount for " + state.line().getLabel(), HttpStatus.BAD_REQUEST);
            }
            if (amount.compareTo(state.balance()) > 0) {
                throw new ApiException(state.balance().signum() == 0
                        ? state.line().getLabel() + " is already paid"
                        : "Only " + state.balance().toPlainString() + " is due for " + state.line().getLabel(),
                        state.balance().signum() == 0 ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST);
            }
        }

        UUID collectionId = UUID.randomUUID();
        List<FeePayment> saved = new ArrayList<>();
        BigDecimal grandTotal = ZERO;
        for (Invoice invoice : invoices) {
            List<LinePaymentRequest> mine = requests.stream()
                    .filter(r -> lines.get(r.invoiceLineId()).getInvoiceId().equals(invoice.getId())).toList();
            BigDecimal fees = mine.stream().map(r -> nz(r.amount())).reduce(ZERO, BigDecimal::add);
            BigDecimal fines = mine.stream().map(r -> nz(r.fine())).reduce(ZERO, BigDecimal::add);
            BigDecimal total = fees.add(fines);

            FeePayment payment = new FeePayment();
            payment.setInvoiceId(invoice.getId());
            payment.setType(PaymentType.PAYMENT);
            payment.setAmount(total);
            payment.setMethod(method);
            payment.setReferenceNumber(blankToNull(referenceNumber));
            payment.setReceiptNumber(paymentRecorder.nextReceiptNumber());
            payment.setCollectedByUserId(collectedBy);
            payment.setNotes(blankToNull(notes));
            payment.setPaymentDate(paymentDate);
            payment.setCollectionId(collectionId);
            FeePayment savedPayment = paymentRepository.save(payment);

            // Keep the ledger order stable: allocations follow the lines' due order.
            List<LinePaymentRequest> ordered = new ArrayList<>(mine);
            ordered.sort(Comparator.comparing((LinePaymentRequest r) -> lines.get(r.invoiceLineId()).getDueDate())
                    .thenComparingInt(r -> lines.get(r.invoiceLineId()).getSequenceOrder()));
            for (LinePaymentRequest r : ordered) {
                if (nz(r.amount()).add(nz(r.fine())).signum() == 0) {
                    continue;
                }
                FeePaymentAllocation allocation = new FeePaymentAllocation();
                allocation.setPaymentId(savedPayment.getId());
                allocation.setInvoiceLineId(r.invoiceLineId());
                allocation.setAmount(nz(r.amount()));
                allocation.setFineAmount(nz(r.fine()));
                allocationRepository.save(allocation);
            }

            invoice.setLateFeeAmount(invoice.getLateFeeAmount().add(fines));
            invoice.setNetAmount(invoice.getAmount().subtract(invoice.getDiscountAmount()).add(invoice.getLateFeeAmount()));
            invoice.setPaidAmount(invoice.getPaidAmount().add(total));
            if (invoice.getPaidAmount().compareTo(invoice.getNetAmount()) > 0) {
                throw new ApiException("This would collect more than the bill", HttpStatus.BAD_REQUEST); // defensive
            }
            InvoiceStatusCalculator.apply(invoice);
            invoiceRepository.save(invoice);

            auditService.log(AuditActions.FEE_PAYMENT_COLLECTED, AuditActions.FEE_PAYMENT, savedPayment.getId(),
                    Map.of("invoiceId", invoice.getId().toString(), "amount", total.toPlainString(),
                            "fine", fines.toPlainString(), "method", method, "receiptNumber",
                            savedPayment.getReceiptNumber(), "collectionId", collectionId.toString(),
                            "lines", mine.size()));
            saved.add(savedPayment);
            grandTotal = grandTotal.add(total);
        }
        return new CollectionOutcome(collectionId, saved, grandTotal);
    }

    // --- Fees Master changes applied to bills already raised -----------------------------------------------------

    /** New amounts for existing lines and lines to add, when a structure's fee lines change. */
    public record LineChange(BigDecimal newTotal, Map<UUID, BigDecimal> updates, List<LineSpec> additions) {
    }

    /**
     * Moves each of the bill's lines by the change of its fee line in the structure (matched by term and name), so
     * a per-student adjustment made at admission survives. Fee lines new to the structure are added. Returns
     * {@code null} when a line would drop below what has already been paid on it -- that bill is left alone.
     */
    @Transactional(readOnly = true)
    public LineChange planStructureChange(Invoice invoice, List<LineSpec> oldSpecs, List<LineSpec> newSpecs) {
        Map<String, BigDecimal> oldByKey = sumByKey(oldSpecs);
        Map<String, BigDecimal> newByKey = sumByKey(newSpecs);
        List<LineState> states = ledger(List.of(invoice)).getOrDefault(invoice.getId(), List.of());
        Map<UUID, BigDecimal> updates = new LinkedHashMap<>();
        Set<String> linesKeys = new HashSet<>();
        BigDecimal total = ZERO;
        for (LineState state : states) {
            InvoiceLine line = state.line();
            String key = key(line.getCategory(), line.getLabel());
            BigDecimal amount = line.getAmount();
            if (linesKeys.add(key) && (oldByKey.containsKey(key) || newByKey.containsKey(key))) {
                amount = amount.add(newByKey.getOrDefault(key, ZERO)).subtract(oldByKey.getOrDefault(key, ZERO));
                if (amount.signum() < 0 || amount.compareTo(state.paid()) < 0) {
                    return null;
                }
                if (amount.compareTo(line.getAmount()) != 0) {
                    updates.put(line.getId(), amount);
                }
            }
            total = total.add(amount);
        }
        boolean anyMatched = linesKeys.stream().anyMatch(k -> oldByKey.containsKey(k) || newByKey.containsKey(k));
        if (!anyMatched && !states.isEmpty()) {
            // The bill doesn't follow the structure's lines (e.g. one line for an amount adjusted at admission):
            // move its first line that can take it by the structure's total change, as before lines existed.
            BigDecimal delta = newSpecs.stream().map(LineSpec::amount).reduce(ZERO, BigDecimal::add)
                    .subtract(oldSpecs.stream().map(LineSpec::amount).reduce(ZERO, BigDecimal::add));
            if (delta.signum() == 0) {
                return new LineChange(total, Map.of(), List.of());
            }
            for (LineState state : states) {
                BigDecimal amount = state.line().getAmount().add(delta);
                if (amount.signum() >= 0 && amount.compareTo(state.paid()) >= 0) {
                    return new LineChange(total.add(delta), Map.of(state.line().getId(), amount), List.of());
                }
            }
            return null;
        }
        List<LineSpec> additions = new ArrayList<>();
        for (LineSpec spec : newSpecs) {
            String key = key(spec.category(), spec.label());
            if (!linesKeys.contains(key) && !oldByKey.containsKey(key)) {
                additions.add(spec);
                total = total.add(spec.amount());
            }
        }
        return new LineChange(total, updates, additions);
    }

    /** Writes a planned change to the bill's lines and re-spreads its discount. */
    @Transactional
    public void applyStructureChange(Invoice invoice, LineChange change) {
        List<InvoiceLine> lines = lineRepository.findByInvoiceIdOrderByDueDateAscSequenceOrderAsc(invoice.getId());
        int nextSequence = lines.stream().mapToInt(InvoiceLine::getSequenceOrder).max().orElse(-1) + 1;
        for (InvoiceLine line : lines) {
            BigDecimal amount = change.updates().get(line.getId());
            if (amount != null) {
                line.setAmount(amount);
            }
        }
        lineRepository.saveAll(lines);
        for (LineSpec spec : change.additions()) {
            InvoiceLine line = new InvoiceLine();
            line.setInvoiceId(invoice.getId());
            line.setLabel(truncate(spec.label(), 150));
            line.setFeeTypeId(spec.feeTypeId());
            line.setCategory(spec.category() == null ? FeeStructureItemCategory.OTHER : spec.category());
            line.setDueDate(spec.dueDate());
            line.setAmount(spec.amount());
            line.setSequenceOrder(nextSequence++);
            lineRepository.save(line);
        }
        lineRepository.flush();
        distributeDiscount(invoice);
    }

    /** True while no line has been paid beyond what is now payable on it. */
    @Transactional(readOnly = true)
    public boolean linesCoverPayments(Invoice invoice) {
        return ledger(List.of(invoice)).getOrDefault(invoice.getId(), List.of()).stream()
                .allMatch(s -> s.paid().compareTo(payable(s.line())) <= 0);
    }

    private static Map<String, BigDecimal> sumByKey(List<LineSpec> specs) {
        Map<String, BigDecimal> map = new HashMap<>();
        specs.forEach(s -> map.merge(key(s.category(), s.label()), s.amount(), BigDecimal::add));
        return map;
    }

    private static String key(String category, String label) {
        return (category == null ? "" : category) + "|" + (label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Total fine a payment collected (its allocations' fines) -- removed from the bill when it is reversed. */
    @Transactional(readOnly = true)
    public BigDecimal fineOf(UUID paymentId) {
        return allocationRepository.findByPaymentId(paymentId).stream()
                .map(FeePaymentAllocation::getFineAmount).reduce(ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
