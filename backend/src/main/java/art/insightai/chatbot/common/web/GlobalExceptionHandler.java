package art.insightai.chatbot.common.web;

import art.insightai.chatbot.chat.domain.ConversationNotFoundException;
import art.insightai.chatbot.chat.domain.ConversationQuotaExceededException;
import art.insightai.chatbot.chat.domain.InvalidModelException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * [Purpose] Single {@code @RestControllerAdvice} mapping domain exceptions to HTTP responses for
 *   every {@code /api/*} controller (chat Spec §5 A-11, T6).
 * [Rationale] Boot 4's default problem representation is RFC 9457 {@link ProblemDetail}
 *   (project CLAUDE.md stack table); this class builds that shape explicitly so every {@code
 *   /api/*} error body has the same fields no matter which controller threw, instead of each
 *   controller throwing its own {@code ResponseStatusException} ad hoc. {@link
 *   ConversationNotFoundException} maps to 404 — see that class's [Rationale] for why an
 *   ownership mismatch is not surfaced as 403. {@link MethodArgumentNotValidException} maps to
 *   400 and covers Bean Validation failures on {@code @Valid} controller arguments; it was added
 *   ahead of any AC that exercised it and now handles {@code CreateConversationRequest.model}'s
 *   {@code @Size(max = 64)} (T6 review 2026-08-27, Medium-1). {@link
 *   ConversationQuotaExceededException} maps to 403, not 429 — see that class's [Rationale] for
 *   why (T6 review 2026-08-27, Medium-2). {@link InvalidModelException} maps to 400 (chat:AC-22,
 *   chat T7) — its message is safe to echo verbatim (see that class's [Caution]), unlike {@code
 *   ConversationQuotaExceededException}.
 * [Caution] Do not wire a domain exception's {@code getMessage()} directly into {@code
 *   ProblemDetail.detail} without checking what that message embeds first — {@link
 *   ConversationQuotaExceededException} used to
 *   do exactly that and leaked its backing config key and numeric ceiling to an unauthenticated
 *   client (T6 review round 2, Medium-1; same pattern as T4's {@code
 *   AnonymousUserQuotaExceededException}, INC-007). Prefer a fixed client-facing string per
 *   exception and push any operator-relevant detail to a WARN log at the throw site instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Generic 403 body (T6 review round 2, Medium-1): the real ceiling and property key stay in
     * {@code ConversationService#createConversation}'s WARN log, not in a client-visible message
     * — mirrors {@code AnonymousSessionFilter#QUOTA_EXCEEDED_CLIENT_MESSAGE} (T4 review round 2).
     */
    private static final String CONVERSATION_QUOTA_EXCEEDED_CLIENT_MESSAGE =
            "conversation limit reached — delete a conversation to create a new one";

    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleConversationNotFound(ConversationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConversationQuotaExceededException.class)
    public ProblemDetail handleConversationQuotaExceeded(ConversationQuotaExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, CONVERSATION_QUOTA_EXCEEDED_CLIENT_MESSAGE);
    }

    @ExceptionHandler(InvalidModelException.class)
    public ProblemDetail handleInvalidModel(InvalidModelException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationFailure(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
}
