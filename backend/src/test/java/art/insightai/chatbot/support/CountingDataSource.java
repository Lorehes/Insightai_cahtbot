package art.insightai.chatbot.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * [Purpose] Wraps a real {@link DataSource} so every {@link Connection#prepareStatement}
 * call across every connection it hands out increments a caller-supplied counter -- lets a test
 * assert "exactly N JDBC statements were prepared" for a given code path (e.g. an N+1 regression
 * guard).
 * [Rationale] Counts at the JDBC statement level -- not by overriding one {@code JdbcTemplate}
 * method -- so it catches a regression no matter which {@code JdbcTemplate}/{@code Connection}
 * method a future change uses to issue the extra query (originally written for
 * {@code ParadeDbBm25SearchAdapterIntegrationTest}'s N+1 guard, review 2026-08-25 High-3; moved
 * here per review 2026-08-26 maintainability Medium-4 so T7 and later cards reading/writing the
 * same table can reuse it instead of re-copying the proxy). Extends
 * {@link DelegatingDataSource} (already on the classpath -- {@code spring-jdbc} is a transitive
 * dependency of {@code spring-boot-starter-data-jpa} in this module's {@code pom.xml}, no new
 * dependency added) instead of a
 * {@link Proxy} for the {@link DataSource} side: {@code DataSource} only has a handful of
 * methods, so overriding just {@link #getConnection()} is simpler than reflecting over all of
 * them (review 2026-08-26 maintainability Medium-4 suggestion). The {@link Connection} side
 * still uses {@link Proxy} because {@code Connection} exposes far more methods and this class
 * only needs to intercept one of them ({@code prepareStatement}).
 * [Caution] Only the no-arg {@link #getConnection()} overload is overridden. Calls made through
 * the two-argument {@link #getConnection(String, String)} overload (username/password) fall
 * through to {@link DelegatingDataSource}'s default, which returns the delegate's connection
 * unwrapped -- statements prepared on that connection are NOT counted. No caller in this
 * codebase uses that overload today; if one starts to, this class must be updated to override it
 * too.
 */
public final class CountingDataSource extends DelegatingDataSource {

    private final AtomicInteger statementCount;

    public CountingDataSource(DataSource delegate, AtomicInteger statementCount) {
        super(delegate);
        this.statementCount = statementCount;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return countingConnection(super.getConnection(), statementCount);
    }

    /**
     * [Purpose] Wraps a real {@link Connection} so every {@code prepareStatement} call on it
     * increments {@code statementCount} before delegating.
     */
    private static Connection countingConnection(Connection delegate, AtomicInteger statementCount) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        statementCount.incrementAndGet();
                    }
                    return invokeUnwrapped(delegate, method, args);
                });
    }

    /**
     * [Purpose] Calls {@code method} on {@code target} and rethrows the original exception
     * instead of {@link InvocationTargetException}, so callers of the proxy above see the same
     * exception a direct (non-reflective) call would have thrown.
     */
    private static Object invokeUnwrapped(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
