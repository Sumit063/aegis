package dev.aegis.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the background jobs (Forwarder, retention); each is active only with its role. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
