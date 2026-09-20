package com.smide.plugins.groovy;

import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;

import java.util.Set;

/**
 * Groovy, and the Gradle build scripts written in it.
 *
 * <p>The words a build script is made of are highlighted along with the language's own:
 * {@code plugins}, {@code dependencies}, {@code implementation}, {@code tasks} are not Groovy
 * keywords - they are methods of Gradle's build language - but in a {@code build.gradle} they
 * are what the file is about, and reading one is much easier when they stand out.
 */
public final class GroovyLanguage implements LanguageSupport {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "as", "assert", "break", "case", "catch", "class", "const", "continue", "def", "default",
            "do", "else", "enum", "extends", "final", "finally", "for", "goto", "if", "implements", "import", "in",
            "instanceof", "interface", "native", "new", "package", "private", "protected", "public", "return",
            "static", "strictfp", "super", "switch", "synchronized", "this", "threadsafe", "throw", "throws",
            "trait", "transient", "try", "var", "volatile", "while", "it", "with", "record", "sealed", "permits",
            "yield", "non-sealed");

    private static final Set<String> TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "String", "Object",
            "List", "Map", "Set", "Closure", "File", "BigDecimal", "BigInteger", "Number", "Date", "Boolean",
            "Integer", "Long", "Double", "Float", "Character", "Byte", "Short", "Exception", "Thread",
            // Gradle's build language, which is what most .gradle files are made of.
            "allprojects", "annotationProcessor", "api", "apply", "archivesBaseName", "artifacts", "bootJar",
            "bootRun", "buildscript", "classpath", "compileOnly", "compileJava", "configurations", "dependencies",
            "description", "developmentOnly", "exclude", "ext", "from", "group", "id", "implementation", "include",
            "jar", "java", "javadoc", "languageVersion", "mainClass", "manifest", "maven", "mavenCentral",
            "mavenLocal", "modularity", "plugins", "processResources", "project", "publishing", "repositories",
            "runtimeOnly", "sourceCompatibility", "sourceSets", "subprojects", "targetCompatibility", "tasks",
            "test", "testCompileOnly", "testImplementation", "testRuntimeOnly", "toolchain", "useJUnitPlatform",
            "version", "war", "wrapper", "settings", "rootProject", "gradle", "distributionUrl");

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");

    private static final GenericLexer LEXER = GenericLexer.builder()
            .keywords(KEYWORDS)
            .types(TYPES.toArray(String[]::new))
            .constants(CONSTANTS.toArray(String[]::new))
            .lineComment("//")
            .blockComment("/*", "*/")
            .docComment("/**")
            // A script's strings are single-quoted as often as double-quoted, and both can run
            // over three quotes; "it's" inside a double-quoted string must not open one.
            .stringQuotes("\"'")
            // No character literals: in Groovy 'a' is a string, not a char.
            .charQuotes("")
            .tripleQuotes(true)
            .annotationPrefix('@')
            .identifierDollar(true)
            .build();

    @Override
    public String id() {
        return "groovy";
    }

    @Override
    public String displayName() {
        return "Groovy";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("groovy", "gradle", "gvy", "gy");
    }

    @Override
    public Set<String> fileNames() {
        return Set.of("Jenkinsfile");
    }

    @Override
    public Highlighter highlighter() {
        return LEXER;
    }

    @Override
    public String lineComment() {
        return "//";
    }

    @Override
    public BlockComment blockComment() {
        return new BlockComment("/*", "*/");
    }

    @Override
    public int indentSize() {
        return 4;
    }
}
