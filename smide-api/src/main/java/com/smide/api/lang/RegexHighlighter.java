package com.smide.api.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A highlighter built from ordered regular-expression rules, for markup and
 * configuration languages where a scanner is overkill: the first rule to match at a
 * position wins.
 *
 * <p>The rules are compiled into one alternation and matched in a single pass, which is
 * what makes "first rule wins" cheap. The rule a match belongs to is then the bracket of
 * the alternation that matched - and that is the part that was wrong: a rule may have
 * brackets of its own. {@code (?s)<script\b[^>]*>.*?(</script>|\z)} contributes two
 * groups, not one, so every rule after it in the list was being read off by the wrong
 * number and painted in the colour of a later rule - HTML's {@code <!DOCTYPE>} came out
 * as a string, its tags as HTML entities, its attribute names as tags - and the rules
 * that ran off the end of the numbering were painted as nothing at all. Thirty-seven of
 * the hundred-odd rules in the IDE have brackets in them, so this was most of a dozen
 * languages: HTML, CSS, JSON, Markdown, YAML, TOML, XML, .properties, CMake, Dockerfile,
 * go.mod and the shells.
 *
 * <p>So each rule's own group number is worked out when the alternation is built, by
 * asking its pattern how many groups it has. A rule that uses a back-reference to its own
 * group is the one thing this cannot carry over, because the number it refers to moves;
 * no rule in the IDE does.
 */
public final class RegexHighlighter implements Highlighter {

    private record Rule(Pattern pattern, TokenType type) {
    }

    /** The alternation, and where each rule's own bracket landed in its numbering. */
    private record Compiled(Pattern pattern, int[] groupOfRule) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private volatile Compiled compiled;

    public RegexHighlighter rule(String regex, TokenType type) {
        rules.add(new Rule(Pattern.compile(regex, Pattern.MULTILINE), type));
        compiled = null;
        return this;
    }

    @Override
    public List<Token> tokenize(String text) {
        Compiled all = compiled();
        int[] groups = all.groupOfRule();
        List<Token> out = new ArrayList<>();
        Matcher m = all.pattern().matcher(text);
        while (m.find()) {
            for (int rule = 0; rule < groups.length; rule++) {
                if (m.group(groups[rule]) != null) {
                    if (m.end() > m.start()) {
                        out.add(new Token(m.start(), m.end(), rules.get(rule).type()));
                    }
                    break;
                }
            }
        }
        return out;
    }

    private synchronized Compiled compiled() {
        Compiled ready = compiled;
        if (ready == null) {
            int[] groups = new int[rules.size()];
            int next = 1;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rules.size(); i++) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                Pattern pattern = rules.get(i).pattern();
                sb.append('(').append(pattern.pattern()).append(')');
                groups[i] = next;
                // This rule's own bracket, plus whatever brackets it brought with it.
                next += 1 + pattern.matcher("").groupCount();
            }
            ready = new Compiled(Pattern.compile(sb.toString(), Pattern.MULTILINE), groups);
            compiled = ready;
        }
        return ready;
    }
}
