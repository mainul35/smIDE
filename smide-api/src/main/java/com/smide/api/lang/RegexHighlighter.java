package com.smide.api.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A highlighter built from ordered regular-expression rules, for markup and
 * configuration languages where a scanner is overkill: the first rule to match at a
 * position wins.
 */
public final class RegexHighlighter implements Highlighter {

    private record Rule(Pattern pattern, TokenType type) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private Pattern combined;

    public RegexHighlighter rule(String regex, TokenType type) {
        rules.add(new Rule(Pattern.compile(regex, Pattern.MULTILINE), type));
        combined = null;
        return this;
    }

    @Override
    public List<Token> tokenize(String text) {
        Pattern all = combined();
        List<Token> out = new ArrayList<>();
        Matcher m = all.matcher(text);
        while (m.find()) {
            for (int g = 1; g <= rules.size(); g++) {
                if (m.group(g) != null) {
                    if (m.end() > m.start()) {
                        out.add(new Token(m.start(), m.end(), rules.get(g - 1).type()));
                    }
                    break;
                }
            }
        }
        return out;
    }

    private synchronized Pattern combined() {
        if (combined == null) {
            StringBuilder sb = new StringBuilder();
            for (Rule rule : rules) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append('(').append(rule.pattern().pattern()).append(')');
            }
            combined = Pattern.compile(sb.toString(), Pattern.MULTILINE);
        }
        return combined;
    }
}
