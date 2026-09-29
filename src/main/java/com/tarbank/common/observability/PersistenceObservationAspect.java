package com.tarbank.common.observability;

import com.tarbank.common.http.CorrelationIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
class PersistenceObservationAspect {
    private final ObservationRegistry observations;

    PersistenceObservationAspect(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Around("execution(* com.tarbank..persistence..*(..))")
    Object observe(ProceedingJoinPoint invocation) throws Throwable {
        String repository = invocation.getSignature()
                                      .getDeclaringType()
                                      .getSimpleName();
        Observation observation = Observation.createNotStarted("tarbank.postgresql", observations)
                                             .lowCardinalityKeyValue("repository", repository)
                                             .lowCardinalityKeyValue(
                                                     "operation", invocation.getSignature()
                                                                            .getName());
        if (CorrelationIdContext.current() != null) {
            observation.highCardinalityKeyValue(
                    "correlation.id", CorrelationIdContext.current()
                                                          .toString());
        }
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            return invocation.proceed();
        } catch (Throwable throwable) {
            observation.error(throwable);
            throw throwable;
        } finally {
            observation.stop();
        }
    }
}
