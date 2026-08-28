package art.insightai.chatbot.common.web;

import art.insightai.chatbot.chat.domain.ConversationNotFoundException;
import art.insightai.chatbot.chat.domain.ConversationQuotaExceededException;
import art.insightai.chatbot.chat.domain.InvalidModelException;
import art.insightai.chatbot.chat.domain.MessageQuotaExceededException;
import art.insightai.chatbot.rag.domain.DocumentQuotaExceededException;
import art.insightai.chatbot.rag.domain.DocumentTooLargeException;
import art.insightai.chatbot.rag.domain.InvalidDocumentFilenameException;
import art.insightai.chatbot.rag.domain.UnsupportedDocumentTypeException;
import art.insightai.chatbot.usage.InvalidUsagePeriodException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
 *   why (T6 review 2026-08-27, Medium-2). {@link MessageQuotaExceededException} maps to 403 for
 *   the same reason, one level down (chat T9 review 2026-08-27, Medium-3; user decision D-3①).
 *   {@link InvalidModelException} maps to 400 (chat:AC-22, chat T7) — its message is safe to echo
 *   verbatim (see that class's [Caution]), unlike {@code ConversationQuotaExceededException}/
 *   {@code MessageQuotaExceededException}.
 * [Caution] Do not wire a domain exception's {@code getMessage()} directly into {@code
 *   ProblemDetail.detail} without checking what that message embeds first — {@link
 *   ConversationQuotaExceededException} used to
 *   do exactly that and leaked its backing config key and numeric ceiling to an unauthenticated
 *   client (T6 review round 2, Medium-1; same pattern as T4's {@code
 *   AnonymousUserQuotaExceededException}, INC-007). Prefer a fixed client-facing string per
 *   exception and push any operator-relevant detail to a WARN log at the throw site instead.
 *   {@link UnsupportedDocumentTypeException} maps to 400 and {@link DocumentTooLargeException} to
 *   413 (document-ingestion:AC-2, AC-3, Tech Review AD-5/AD-6); {@link
 *   MaxUploadSizeExceededException} — Spring's own exception for the container-level multipart
 *   limit ({@code spring.servlet.multipart.max-file-size}) — maps to the same 413 through a
 *   separate handler, since an oversized request never reaches the application-level check that
 *   throws {@link DocumentTooLargeException} (Tech Review AD-6). {@link
 *   DocumentQuotaExceededException} maps to 403, same status and same "fixed client string, no
 *   config leak" shape as {@link ConversationQuotaExceededException}/{@link
 *   MessageQuotaExceededException} above (document-ingestion §5, review 2026-08-25 Medium-2,
 *   INC-007). {@link InvalidDocumentFilenameException} maps to 400, same shape one level down
 *   (document-ingestion §5, review 2026-08-28 High-1). Unlike {@link InvalidModelException} above,
 *   {@link #handleUnsupportedDocumentType} no longer echoes {@code e.getMessage()} verbatim either
 *   (review 2026-08-28 security Low-1): the caller-supplied {@code Content-Type} header it embeds
 *   is unbounded and a D3 admin screen is expected to render this endpoint's error responses, so
 *   the handler now returns a fixed {@code detail} and surfaces the header value through a
 *   separate, length-capped {@code ProblemDetail} property instead. {@link
 *   InvalidUsagePeriodException} maps to 400 (eval-usage-dashboard:AC-15, Tech Review D-8) and,
 *   like {@link InvalidModelException} above, echoes {@code e.getMessage()} verbatim — see that
 *   class's own [Caution] for why embedding {@code from}/{@code to} is safe.
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

    /**
     * Generic 403 body (chat T9 review 2026-08-27, Medium-3; INC-007): the real ceiling and
     * property key stay in {@code ConversationService#enforceMessageQuota}'s WARN log — same
     * shape as {@link #CONVERSATION_QUOTA_EXCEEDED_CLIENT_MESSAGE} above, one level down.
     */
    private static final String MESSAGE_QUOTA_EXCEEDED_CLIENT_MESSAGE =
            "message limit reached for this conversation — start a new conversation to continue";

    /**
     * Generic 403 body (document-ingestion §5, review 2026-08-25 Medium-2; INC-007): the real
     * ceiling and property key stay in {@code DocumentUploadService#upload}'s WARN log, not in a
     * client-visible message — same shape as {@link #CONVERSATION_QUOTA_EXCEEDED_CLIENT_MESSAGE}/
     * {@link #MESSAGE_QUOTA_EXCEEDED_CLIENT_MESSAGE} above.
     */
    private static final String DOCUMENT_QUOTA_EXCEEDED_CLIENT_MESSAGE =
            "document limit reached — delete a document to upload a new one";

    /**
     * Generic 413 body shared by {@link #handleDocumentTooLarge}/{@link
     * #handleMaxUploadSizeExceeded} (document-ingestion Tech Review AD-6): both routes mean the
     * same thing to the caller regardless of which of the two size guards actually tripped.
     */
    private static final String DOCUMENT_TOO_LARGE_CLIENT_MESSAGE =
            "uploaded file exceeds the maximum allowed size";

    /**
     * Generic 400 body (document-ingestion §5, review 2026-08-28 High-1; INC-007): whether the
     * filename was missing, blank, or too long stays out of the client-visible message — same
     * "fixed client string, no argument" shape as {@link #DOCUMENT_QUOTA_EXCEEDED_CLIENT_MESSAGE}
     * above, one status code down.
     */
    private static final String INVALID_DOCUMENT_FILENAME_CLIENT_MESSAGE =
            "uploaded file must have a name no longer than the server allows";

    /**
     * Cap on the caller-supplied {@code Content-Type} value surfaced back to the client via
     * {@link #handleUnsupportedDocumentType}'s {@code mimeType} property (review 2026-08-28
     * security Low-1) — bounds what an unbounded request header can make this endpoint's error
     * response render, independent of the fixed {@link #UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE}.
     */
    private static final int ECHOED_MIME_TYPE_MAX_LENGTH = 128;

    /**
     * Generic 400 body (document-ingestion:AC-2, review 2026-08-28 security Low-1): the rejected
     * {@code Content-Type} itself is not embedded here — see {@link #handleUnsupportedDocumentType}.
     */
    private static final String UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE =
            "unsupported document MIME type";

    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleConversationNotFound(ConversationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConversationQuotaExceededException.class)
    public ProblemDetail handleConversationQuotaExceeded(ConversationQuotaExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, CONVERSATION_QUOTA_EXCEEDED_CLIENT_MESSAGE);
    }

    @ExceptionHandler(MessageQuotaExceededException.class)
    public ProblemDetail handleMessageQuotaExceeded(MessageQuotaExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, MESSAGE_QUOTA_EXCEEDED_CLIENT_MESSAGE);
    }

    @ExceptionHandler(InvalidModelException.class)
    public ProblemDetail handleInvalidModel(InvalidModelException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * [Purpose] Maps {@link UnsupportedDocumentTypeException} to 400.
     * [Rationale] Returns a fixed {@code detail} instead of {@code e.getMessage()} (review
     *   2026-08-28 security Low-1) and surfaces the rejected {@code Content-Type} separately,
     *   truncated to {@link #ECHOED_MIME_TYPE_MAX_LENGTH} — see class-level [Rationale].
     */
    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    public ProblemDetail handleUnsupportedDocumentType(UnsupportedDocumentTypeException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNSUPPORTED_DOCUMENT_TYPE_CLIENT_MESSAGE);
        if (e.mimeType() != null) {
            problem.setProperty("mimeType", truncate(e.mimeType(), ECHOED_MIME_TYPE_MAX_LENGTH));
        }
        return problem;
    }

    @ExceptionHandler(InvalidDocumentFilenameException.class)
    public ProblemDetail handleInvalidDocumentFilename(InvalidDocumentFilenameException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, INVALID_DOCUMENT_FILENAME_CLIENT_MESSAGE);
    }

    @ExceptionHandler(DocumentTooLargeException.class)
    public ProblemDetail handleDocumentTooLarge(DocumentTooLargeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, DOCUMENT_TOO_LARGE_CLIENT_MESSAGE);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, DOCUMENT_TOO_LARGE_CLIENT_MESSAGE);
    }

    @ExceptionHandler(DocumentQuotaExceededException.class)
    public ProblemDetail handleDocumentQuotaExceeded(DocumentQuotaExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, DOCUMENT_QUOTA_EXCEEDED_CLIENT_MESSAGE);
    }

    @ExceptionHandler(InvalidUsagePeriodException.class)
    public ProblemDetail handleInvalidUsagePeriod(InvalidUsagePeriodException e) {
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

    /**
     * [Purpose] Caps {@code value} at {@code maxLength} UTF-16 code units for {@link
     *   #handleUnsupportedDocumentType}'s echoed {@code mimeType} property (review 2026-08-28
     *   security Low-1).
     * [Rationale] A plain {@code substring(0, maxLength)} cuts by UTF-16 code unit and can leave
     *   a lone, unpaired high surrogate at the end of the returned string if {@code maxLength}
     *   happens to fall inside a surrogate pair. This is not known to be reachable through the
     *   real HTTP path today -- Tomcat decodes header bytes as ISO-8859-1, so a caller-supplied
     *   {@code Content-Type} cannot itself carry a character outside that range -- but the check
     *   below closes the gap structurally instead of relying on that (review 2026-08-28
     *   security Low-2).
     */
    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int cutIndex = maxLength;
        if (Character.isHighSurrogate(value.charAt(cutIndex - 1))) {
            cutIndex--;
        }
        return value.substring(0, cutIndex);
    }
}
