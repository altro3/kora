package io.koraframework.common.telemetry;

import io.koraframework.common.context.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import org.jspecify.annotations.*;

public interface Observation {

    KoraContext.Key<Observation> OBSERVATION_KEY = KoraContext.Key.create();

    Span span();

    static <T extends Observation> @Nullable T current(Class<T> clazz) {
        KoraContext current = KoraContext.current();
        if (current == null) {
            return null;
        }
        Observation obs = current.get(OBSERVATION_KEY);
        return obs != null ? clazz.cast(obs) : null;
    }

    void end();

    void observeError(Throwable e);

    /**
     * Строит новый плоский KoraContext, лениво добавляя в него обсервацию
     * и обновленный контекст трассировки OpenTelemetry.
     */
    static KoraContext attach(KoraContext current, Observation observation) {
        var updated = current.with(OBSERVATION_KEY, observation);

        if (observation.span().getSpanContext().isValid()) {
            Context otelCtx = current.get(OpentelemetryContextStorage.OPENTELEMETRY_KEY);
            if (otelCtx == null) {
                otelCtx = Context.current();
            }
            return updated.with(OpentelemetryContextStorage.OPENTELEMETRY_KEY, otelCtx.with(observation.span()));
        }

        return updated;
    }

    /**
     * Восстанавливает предыдущую обсервацию и контекст OpenTelemetry в KoraContext.
     */
    static void detach(@Nullable KoraContext current, @Nullable Observation prevObs, @Nullable Context prevOtel) {
        if (current == null) {
            return;
        }
        current.with(OBSERVATION_KEY, prevObs)
            .with(OpentelemetryContextStorage.OPENTELEMETRY_KEY, prevOtel);
    }
}
