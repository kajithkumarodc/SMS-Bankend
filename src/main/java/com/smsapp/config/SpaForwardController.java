package com.smsapp.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the SPA's {@code index.html} for any client-side route that has no
 * matching server route of its own.
 *
 * <p>The frontend's build output lives in {@code src/main/resources/static}
 * (copied there by the combined build -- see the project's Containerfile),
 * and React Router handles navigation entirely in the browser. A fresh
 * request for a deep link such as {@code /students/12} therefore has no
 * corresponding Spring route, and without this controller would 404 instead
 * of loading the app and letting the client-side router take over.
 *
 * <p>The mapping only needs to cover paths that are neither an API call
 * (those live under {@code /api/**}, handled by the application's own
 * controllers) nor a real static file (JS/CSS/images, which Spring's static
 * resource handler already serves directly and which this mapping never
 * sees because it does not match dotted filenames). Anything else is
 * assumed to be a client-side route and gets the SPA shell.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/",
        "/{path:[^.]*}",
        "/**/{path:[^.]*}"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
