package io.koraframework.database.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.koraframework.application.graph.Lifecycle;
import io.koraframework.application.graph.Wrapped;
import io.koraframework.common.Configurer;
import io.koraframework.common.readiness.ReadinessProbe;
import io.koraframework.common.readiness.ReadinessProbeFailure;
import io.koraframework.common.util.TimeUtils;
import io.koraframework.database.common.telemetry.DatabaseTelemetry;
import io.koraframework.database.common.telemetry.DatabaseTelemetryFactory;
import io.koraframework.database.jdbc.exception.UncheckedSqlException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.*;

public class JdbcDataSource implements Lifecycle, Wrapped<DataSource>, JdbcExecutor, ReadinessProbe {

    private static final Logger logger = LoggerFactory.getLogger(JdbcDataSource.class);

    static {
        try {
            Class.forName("org.postgresql.Driver");
            Class.forName("com.zaxxer.hikari.pool.ProxyConnection");
            Class.forName("com.zaxxer.hikari.pool.ProxyStatement");
            Class.forName("com.zaxxer.hikari.pool.ProxyPreparedStatement");
            Class.forName("com.zaxxer.hikari.pool.ProxyResultSet");
            Class.forName("com.zaxxer.hikari.util.ConcurrentBag");
            Class.forName("com.zaxxer.hikari.pool.HikariPool$PoolInitializationException");
            Class.forName("java.util.concurrent.ScheduledThreadPoolExecutor");
            Class.forName("java.util.concurrent.ThreadPoolExecutor$Worker");
            java.util.logging.Logger.getLogger("org.postgresql");
        } catch (ClassNotFoundException ignored) {
        }
    }
    private final JdbcDatabaseConfig databaseConfig;
    private final HikariDataSource dataSource;
    private final DatabaseTelemetry telemetry;
    private final ScopedValue<ConnectionContext> connectionContext = ScopedValue.newInstance();

    public JdbcDataSource(JdbcDatabaseConfig config, DatabaseTelemetryFactory telemetryFactory, @Nullable Configurer<HikariConfig> configurer) {
        this.databaseConfig = Objects.requireNonNull(config);
        var jdbcUrl = config.jdbcUrl();
        var jdbcDatabase = jdbcUrl.substring(5, jdbcUrl.indexOf(":", 5));
        this.telemetry = telemetryFactory.get(
            config.telemetry(),
            config.poolName(),
            jdbcDatabase
        );

        var hikariConfig = JdbcDatabaseConfig.toHikariConfig(this.databaseConfig, configurer);

        var threadFactory = Thread.ofVirtual().name("hikari-vt-", 1).factory();
        hikariConfig.setThreadFactory(threadFactory);

        if (this.databaseConfig.telemetry().metrics().driverMetrics()) {
            hikariConfig.setMetricRegistry(this.telemetry.meterRegistry());
        }
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    @Override
    public void init() throws SQLException {
        logger.info("JdbcDataSource pool '{}' is hot and ready.", databaseConfig.poolName());
    }

    @Override
    public void release() {
        logger.debug("JdbcDataSource pool '{}' stopping...", databaseConfig.poolName());
        var started = System.nanoTime();

        this.dataSource.close();

        logger.info("JdbcDataSource pool '{}' stopped in {}", databaseConfig.poolName(), TimeUtils.tookForLogging(started));
    }

    @Override
    public DataSource value() {
        return this.dataSource;
    }

    @Nullable
    @Override
    public Connection acquireConnection() {
        try {
            return this.dataSource.getConnection();
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        }
    }

    @Override
    public DatabaseTelemetry telemetry() {
        return this.telemetry;
    }

    @Nullable
    public Connection connectionCurrent() {
        if (this.connectionContext.isBound()) {
            return this.connectionContext.get().connection();
        }
        return null;
    }

    @Nullable
    public ConnectionContext currentContext() {
        if (this.connectionContext.isBound()) {
            return this.connectionContext.get();
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T withContext(SqlFunction<ConnectionContext, T> callback) throws UncheckedSqlException {
        if (this.connectionContext.isBound()) {
            try {
                return callback.apply(this.connectionContext.get());
            } catch (SQLException e) {
                throw new UncheckedSqlException(e);
            }
        }

        try (var connection = this.acquireConnection()) {
            var context = new ConnectionContext(connection);
            var action = new ScopedValueCallAction(callback, context);
            return (T) ScopedValue.where(this.connectionContext, context).call(action);
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    public ReadinessProbeFailure probe() throws Exception {
        if (this.databaseConfig.readinessProbe()) {
            try (var c = this.dataSource.getConnection()) {
                c.isValid((int) this.databaseConfig.validationTimeout().toMillis());
            }
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static final class ScopedValueCallAction implements ScopedValue.CallableOp<Object, Exception> {
        private final SqlFunction callback;
        private final ConnectionContext context;

        private ScopedValueCallAction(SqlFunction callback, ConnectionContext context) {
            this.callback = callback;
            this.context = context;
        }

        @Override
        public Object call() throws Exception {
            return callback.apply(context);
        }
    }
}
