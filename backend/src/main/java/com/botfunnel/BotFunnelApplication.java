package com.botfunnel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Excludes JobRunrMetricsAutoConfiguration by NAME (not class literal): it declares
// @AutoConfiguration(after = { …, MetricsAutoConfiguration.class, … }) with class-literal
// values to actuator classes. Once we add a MeterRegistry bean, Spring's autoconfig sorter
// resolves those `after`-references and throws ClassNotFoundException because actuator is
// intentionally absent (Decision 10). Excluding the autoconfig by string name avoids
// importing the actuator class at compile time. JobRunr's own micrometer integration is
// unused here — all webhook counters are wired directly against the explicit
// SimpleMeterRegistry bean.
@SpringBootApplication(excludeName = "org.jobrunr.spring.autoconfigure.metrics.JobRunrMetricsAutoConfiguration")
public class BotFunnelApplication {

	public static void main(String[] args) {
		SpringApplication.run(BotFunnelApplication.class, args);
	}

}
