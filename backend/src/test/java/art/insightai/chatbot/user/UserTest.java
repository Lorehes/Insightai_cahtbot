package art.insightai.chatbot.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies {@link User}'s construction behavior. No Spring context or database is
 *   involved; this is pure object behavior. {@code id} is intentionally not asserted here — it
 *   is left {@code null} until Hibernate assigns it on insert (see class-level [Rationale]),
 *   unlike {@code rag.domain.Document}.
 */
class UserTest {

    @Test
    @DisplayName("생성 직후 createdAt이 채워지고 id는 아직 비어 있다")
    void newUserHasCreatedAtButNoIdYet() {
        // given / when: a user is constructed for an anonymous session
        User user = new User("anonymous", null, null);

        // then: createdAt is already stamped, id waits for Hibernate to assign it on insert
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getId()).isNull();
        assertThat(user.getAuthProvider()).isEqualTo("anonymous");
        assertThat(user.getExternalId()).isNull();
        assertThat(user.getDisplayName()).isNull();
    }
}
