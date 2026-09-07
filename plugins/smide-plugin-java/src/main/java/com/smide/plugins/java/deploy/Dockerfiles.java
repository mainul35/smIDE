package com.smide.plugins.java.deploy;

import com.smide.plugins.java.JavaProjectInfo;

/** A sensible multi-stage Dockerfile for a Maven or Gradle Java service. */
public final class Dockerfiles {

    private Dockerfiles() {
    }

    public static String forProject(JavaProjectInfo p) {
        int java = p.javaVersion() > 0 ? p.javaVersion() : 21;
        String jdk = "eclipse-temurin:" + java + "-jdk";
        String jre = "eclipse-temurin:" + java + "-jre";
        if (p.isGradle()) {
            return """
                    # Build stage: compiles with Gradle, so the image does not need the host's toolchain.
                    FROM %s AS build
                    WORKDIR /workspace
                    COPY . .
                    RUN chmod +x gradlew 2>/dev/null || true
                    RUN ./gradlew --no-daemon clean assemble -x test || gradle --no-daemon clean assemble -x test

                    # Run stage: only the JRE and the jar.
                    FROM %s
                    WORKDIR /app
                    COPY --from=build /workspace/build/libs/*.jar app.jar
                    EXPOSE 8080
                    ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                    """.formatted(jdk, jre);
        }
        return """
                # Build stage: resolves dependencies first so they are cached between builds.
                FROM %s AS build
                WORKDIR /workspace
                COPY pom.xml .
                COPY .mvn/ .mvn/
                COPY mvnw .
                RUN chmod +x mvnw 2>/dev/null || true
                RUN (./mvnw -q -B dependency:go-offline || mvn -q -B dependency:go-offline) || true
                COPY src ./src
                RUN ./mvnw -q -B clean package -DskipTests || mvn -q -B clean package -DskipTests

                # Run stage: only the JRE and the jar.
                FROM %s
                WORKDIR /app
                COPY --from=build /workspace/target/*.jar app.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(jdk, jre);
    }
}
