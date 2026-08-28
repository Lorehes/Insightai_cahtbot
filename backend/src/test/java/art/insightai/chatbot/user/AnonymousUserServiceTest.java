package art.insightai.chatbot.user;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link AnonymousUserService#resolveOrCreate}'s three branches — reuse,
 *   missing cookie, malformed/unknown UUID — without a database ({@link UserRepository} mocked).
 *   {@code app.session.max-anonymous-users} quota rejection (T4 review, High-2) gets its own
 *   coverage from dev-qa-tester; the {@link #DEFAULT_APP_PROPERTIES} constant here only supplies
 *   a generous ceiling so these pre-existing branches keep passing under the new constructor.
 * [Caution] {@code DEFAULT_APP_PROPERTIES} is {@link TestAppPropertiesFactory}'s valid baseline
 *   as-is (T5 review 2026-08-27, Medium-4) -- its {@code AppProperties.Models} component (chat
 *   T5's 6th record component) is unrelated to session handling and stays at the factory's valid
 *   default purely to satisfy {@code AppProperties.Models}'s fail-fast compact constructor;
 *   {@link AnonymousUserService} never reads {@code AppProperties.models()}.
 */
@ExtendWith(MockitoExtension.class)
class AnonymousUserServiceTest {

    private static final AppProperties DEFAULT_APP_PROPERTIES = TestAppPropertiesFactory.valid();

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("[chat:AC-10] 존재하는 UUID 쿠키 값이면 그대로 재사용하고 새 행을 만들지 않는다")
    void reusesExistingUserWithoutSaving() {
        // given
        UUID existingId = UUID.randomUUID();
        when(userRepository.existsByIdAndAuthProvider(existingId, "anonymous")).thenReturn(true);
        AnonymousUserService service = new AnonymousUserService(userRepository, DEFAULT_APP_PROPERTIES);

        // when
        UUID result = service.resolveOrCreate(existingId.toString());

        // then
        assertThat(result).isEqualTo(existingId);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[chat:AC-9] 쿠키 값이 null이면 새 anonymous User를 생성한다")
    void createsNewUserWhenCookieIsNull() {
        // given: `saved` (the id-bearing return value) stays a mock -- User#id has no setter, it
        //   is Hibernate-generated -- but the captor intercepts the actual, real User instance
        //   AnonymousUserService constructs, so authProvider is checked on real production code,
        //   not on a mock's stubbed answer (review Low, round-2 follow-up: the prior version of
        //   this test only verified the mocked id round-trip and could not have caught
        //   AnonymousUserService constructing e.g. new User("google", ...) by mistake)
        ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
        User saved = mock(User.class);
        UUID newId = UUID.randomUUID();
        when(saved.getId()).thenReturn(newId);
        when(userRepository.save(savedUserCaptor.capture())).thenReturn(saved);
        AnonymousUserService service = new AnonymousUserService(userRepository, DEFAULT_APP_PROPERTIES);

        // when
        UUID result = service.resolveOrCreate(null);

        // then
        assertThat(result).isEqualTo(newId);
        verify(userRepository, never()).existsByIdAndAuthProvider(any(), any());
        assertThat(savedUserCaptor.getValue().getAuthProvider()).isEqualTo("anonymous");
    }

    @Test
    @DisplayName("[chat:AC-12] UUID 형식이 아닌 값이면 조회 없이 신규 발급한다")
    void createsNewUserWhenCookieIsNotAWellFormedUuid() {
        // given
        User saved = mock(User.class);
        UUID newId = UUID.randomUUID();
        when(saved.getId()).thenReturn(newId);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        AnonymousUserService service = new AnonymousUserService(userRepository, DEFAULT_APP_PROPERTIES);

        // when
        UUID result = service.resolveOrCreate("not-a-uuid");

        // then
        assertThat(result).isEqualTo(newId);
        verify(userRepository, never()).existsByIdAndAuthProvider(any(), any());
    }

    @Test
    @DisplayName("[chat:AC-12] UUID 형식이지만 DB에 없으면 신규 발급한다")
    void createsNewUserWhenUuidIsWellFormedButUnknown() {
        // given
        UUID unknownId = UUID.randomUUID();
        User saved = mock(User.class);
        UUID newId = UUID.randomUUID();
        when(userRepository.existsByIdAndAuthProvider(unknownId, "anonymous")).thenReturn(false);
        when(saved.getId()).thenReturn(newId);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        AnonymousUserService service = new AnonymousUserService(userRepository, DEFAULT_APP_PROPERTIES);

        // when
        UUID result = service.resolveOrCreate(unknownId.toString());

        // then
        assertThat(result).isEqualTo(newId);
    }

    @Test
    @DisplayName("상한에 도달하면 신규 발급 대신 AnonymousUserQuotaExceededException을 던진다")
    void throwsQuotaExceededWhenCeilingReached() {
        // given: no cookie -- the reuse branch is skipped, so this always attempts a new issue
        AppProperties lowCeiling = TestAppPropertiesFactory.builder()
                .session(new AppProperties.Session(5, 50, 200))
                .build();
        when(userRepository.countByAuthProvider("anonymous")).thenReturn(5L);
        AnonymousUserService service = new AnonymousUserService(userRepository, lowCeiling);

        // when / then
        assertThatThrownBy(() -> service.resolveOrCreate(null))
                .isInstanceOf(AnonymousUserQuotaExceededException.class);
        verify(userRepository, never()).save(any());
    }
}
