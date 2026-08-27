package art.insightai.chatbot.eval.evalset;

/**
 * [Purpose] Thrown when an eval-set YAML file violates the schema — a malformed or incomplete item
 *   fails loading loudly instead of being silently skipped.
 * [Rationale] AC-2 explicitly requires a hard failure ("조용히 건너뛰지 않는다") so a typo in the
 *   evaluation set cannot quietly shrink the sample size that AC-1's 20-item count depends on.
 * [AC] eval-usage-dashboard:AC-2
 */
public class EvalSetValidationException extends RuntimeException {

    public EvalSetValidationException(String message) {
        super(message);
    }

    public EvalSetValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
