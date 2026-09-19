package com.smide.plugins.springboot;

import com.smide.plugins.java.JavaProjectInfo;

/** What kind of Spring project a Java project is, from what its build uses. */
final class SpringBoot {

    private SpringBoot() {
    }

    /** Spring Boot on the build: a starter, the parent, the Maven plugin, or the Gradle plugin. */
    static boolean isSpringBoot(JavaProjectInfo project) {
        return project.uses("org.springframework.boot") || project.uses("plugin:org.springframework.boot")
                || project.artifacts().stream().anyMatch(a -> a.substring(a.indexOf(':') + 1).startsWith("spring-boot"));
    }

    /** The user's Spring Lens, for runtime insight, on the build. */
    static boolean hasSpringLens(JavaProjectInfo project) {
        return project.artifacts().stream().anyMatch(a -> a.contains("spring-lens"));
    }
}
