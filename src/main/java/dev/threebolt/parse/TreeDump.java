package dev.threebolt.parse;

import dev.threebolt.parser.ThreeboltParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Compact s-expression rendering of a parse tree, used by `bolt debug parse` and the parser tests.
 * Rule nodes with a single child are elided, so {@code 1 + 2 * 3} renders as
 * {@code (additiveExpr 1 + (multiplicativeExpr 2 * 3))}. Labeled alternatives show their label.
 */
public final class TreeDump {
    private TreeDump() {}

    public static String render(ParseTree tree) {
        var out = new StringBuilder();
        render(tree, out);
        return out.toString();
    }

    private static void render(ParseTree tree, StringBuilder out) {
        if (tree instanceof ErrorNode e) {
            out.append("<error ").append(TokenDump.escape(e.getText())).append('>');
            return;
        }
        if (tree instanceof TerminalNode t) {
            out.append(TokenDump.escape(t.getText()));
            return;
        }
        while (tree.getChildCount() == 1 && !(tree.getChild(0) instanceof TerminalNode)) {
            tree = tree.getChild(0);
        }
        if (tree.getChildCount() == 1) {
            render(tree.getChild(0), out);
            return;
        }
        out.append('(').append(name((ParserRuleContext) tree));
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParseTree child = tree.getChild(i);
            if (child instanceof TerminalNode t && t.getSymbol().getType() == ThreeboltParser.EOF) continue;
            out.append(' ');
            render(child, out);
        }
        out.append(')');
    }

    /** The labeled alternative's name if there is one, else the rule name. */
    private static String name(ParserRuleContext ctx) {
        String cls = ctx.getClass().getSimpleName();
        String base = cls.substring(0, cls.length() - "Context".length());
        String rule = ThreeboltParser.ruleNames[ctx.getRuleIndex()];
        return base.equalsIgnoreCase(rule) ? rule : Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }
}
