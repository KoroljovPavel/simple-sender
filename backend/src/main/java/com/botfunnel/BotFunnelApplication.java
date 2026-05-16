package com.botfunnel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Excludes JobRunrMetricsAutoConfiguration by NAME (not class literal): it declares
// @ConditionalOnClass(MetricsAutoConfiguration.class) with a class-literal value, and
// Spring's AnnotationBeanNameGenerator resolves all annotation class references during
// bean-definition registration — before the conditional fires. With actuator off the
// classpath (Decision 10) that resolution throws ClassNotFoundException at boot. JobRunr's
// own micrometer integration is unused here; all webhook counters are wired directly
// against the explicit SimpleMeterRegistry bean.
@SpringBootApplication(excludeName = "org.jobrunr.spring.autoconfigure.metrics.JobRunrMetricsAutoConfiguration")
public class BotFunnelApplication {

	public static void main(String[] args) {
		SpringApplication.run(BotFunnelApplication.class, args);
	}

}
