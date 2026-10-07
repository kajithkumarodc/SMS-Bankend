package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {

    List<InvoiceLine> findByInvoiceIdOrderByDueDateAscSequenceOrderAsc(UUID invoiceId);

    List<InvoiceLine> findByInvoiceIdIn(Collection<UUID> invoiceIds);
}
