package com.smide.plugins.sql;

import com.smide.api.lang.FileType;
import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** SQL: queries, DDL and DML. No language server; a lexer that knows the words. */
public final class SqlPlugin implements Plugin {

    private static final String[] KEYWORDS = {
            "add", "all", "alter", "analyze", "and", "as", "asc", "begin", "between", "by", "cascade", "case",
            "cast", "check", "column", "commit", "constraint", "create", "cross", "cube", "current", "database",
            "declare", "default", "delete", "desc", "distinct", "drop", "else", "end", "escape", "except",
            "exists", "explain", "false", "fetch", "first", "for", "foreign", "from", "full", "function",
            "grant", "group", "having", "if", "ilike", "in", "index", "inner", "insert", "intersect", "into",
            "is", "join", "key", "left", "like", "limit", "materialized", "merge", "natural", "not", "null",
            "nulls", "offset", "on", "or", "order", "outer", "over", "partition", "primary", "procedure",
            "references", "rename", "replace", "restrict", "returning", "revoke", "right", "rollback", "row",
            "rows", "schema", "select", "sequence", "set", "show", "some", "table", "temporary", "then", "to",
            "transaction", "trigger", "true", "truncate", "union", "unique", "update", "using", "vacuum",
            "values", "view", "when", "where", "window", "with"};

    private static final String[] TYPES = {
            "bigint", "binary", "bit", "blob", "boolean", "bytea", "char", "character", "clob", "date",
            "datetime", "decimal", "double", "float", "int", "integer", "interval", "json", "jsonb", "money",
            "numeric", "real", "serial", "smallint", "text", "time", "timestamp", "timestamptz", "tinyint",
            "uuid", "varbinary", "varchar", "xml"};

    private static final String[] FUNCTIONS = {
            "avg", "coalesce", "count", "current_date", "current_timestamp", "extract", "greatest", "least",
            "lower", "max", "min", "now", "nullif", "round", "substring", "sum", "trim", "upper"};

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("sql", "SQL",
                Set.of("sql", "ddl", "dml", "psql", "mysql", "pgsql"), Set.of(), "fth-database", false));
        context.registerLanguage(new SqlLanguage());
    }

    /**
     * SQL is written in both cases, and the lexer matches words exactly, so every keyword
     * is registered lower-case, upper-case and capitalised.
     */
    private static Set<String> allCases(String... words) {
        Set<String> out = new HashSet<>();
        for (String word : words) {
            out.add(word);
            out.add(word.toUpperCase(Locale.ROOT));
            out.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out;
    }

    private static final class SqlLanguage implements LanguageSupport {

        private static final Highlighter LEXER = GenericLexer.builder()
                .keywords(allCases(KEYWORDS))
                .types(allCases(TYPES).toArray(String[]::new))
                .constants("true", "false", "null", "TRUE", "FALSE", "NULL")
                .lineComment("--")
                .blockComment("/*", "*/")
                .stringQuotes("'")
                .charQuotes("")
                .functionCalls(true)
                .build();

        @Override
        public String id() {
            return "sql";
        }

        @Override
        public String displayName() {
            return "SQL";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("sql", "ddl", "dml", "psql", "mysql", "pgsql");
        }

        @Override
        public Highlighter highlighter() {
            return LEXER;
        }

        @Override
        public String lineComment() {
            return "--";
        }

        @Override
        public BlockComment blockComment() {
            return new BlockComment("/*", "*/");
        }

        @Override
        public String bracketPairs() {
            return "()";
        }

        @Override
        public String indentOpeners() {
            return "(";
        }
    }
}
