// Threebolt lexer — spec §1.
//
// The lexer never fails: malformed input becomes an INVALID_* / UNTERMINATED_* /
// UNEXPECTED_CHAR token, which the front end turns into a diagnostic
// (dev.threebolt.parse.LexErrors).
//
// `>` is always its own token: `>=`, `>>` and `>>=` are assembled by the parser from
// adjacent tokens, so `List<List<i32>>` and `fn f() -> Box<T>=> x` need no splitting.
lexer grammar ThreeboltLexer;

channels { DOC }

tokens { INTERP_END }

@lexer::members {
    // Type of the last token emitted on the default channel; used to lex `t.0.1` as tuple access.
    private int lastType = Token.INVALID_TYPE;

    // One entry per open f-string interpolation (innermost first): its count of unclosed `{`.
    private final java.util.ArrayDeque<Integer> braceDepth = new java.util.ArrayDeque<>();

    private void openInterpolation() { braceDepth.push(0); }

    private void openBrace() {
        if (!braceDepth.isEmpty()) braceDepth.push(braceDepth.pop() + 1);
    }

    private void closeBrace() {
        if (braceDepth.isEmpty()) return;
        int depth = braceDepth.pop();
        if (depth > 0) {
            braceDepth.push(depth - 1);
        } else {
            setType(INTERP_END);
            popMode();
        }
    }

    @Override
    public Token nextToken() {
        Token t = super.nextToken();
        if (t.getType() == EOF && (_mode != DEFAULT_MODE || !_modeStack.isEmpty())) {
            // EOF inside an f-string: report it once, at EOF, then return to a clean state.
            _modeStack.clear();
            _mode = DEFAULT_MODE;
            braceDepth.clear();
            return _factory.create(_tokenFactorySourcePair, UNTERMINATED_FSTRING, "", DEFAULT_TOKEN_CHANNEL,
                    t.getStartIndex(), t.getStartIndex() - 1, t.getLine(), t.getCharPositionInLine());
        }
        if (t.getChannel() == DEFAULT_TOKEN_CHANNEL) lastType = t.getType();
        return t;
    }

    @Override
    public void reset() {
        super.reset();
        lastType = Token.INVALID_TYPE;
        braceDepth.clear();
    }
}

// ---- Whitespace and comments ----

BOM                  : '﻿' { _tokenStartCharIndex == 0 }? -> skip ;
WS                   : [ \t\r\n\f]+ -> skip ;
DOC_COMMENT          : '///' (~[/\r\n] ~[\r\n]*)? -> channel(DOC) ;
LINE_COMMENT         : '//' ~[\r\n]* -> skip ;
// Nested: an inner `/*` always opens a comment that must itself close.
BLOCK_COMMENT
    : '/*' ( BLOCK_COMMENT | '/' { _input.LA(1) != '*' }? | '*' { _input.LA(1) != '/' }? | ~[/*] )* '*/' -> skip
    ;
// Only wins when BLOCK_COMMENT cannot match, i.e. the comment never closes.
UNTERMINATED_COMMENT : '/*' ;

// ---- Keywords ----

ABSTRACT  : 'abstract' ;
AS        : 'as' ;
BREAK     : 'break' ;
CLASS     : 'class' ;
CONST     : 'const' ;
CONTINUE  : 'continue' ;
DEFER     : 'defer' ;
ELSE      : 'else' ;
ENUM      : 'enum' ;
EXTERN    : 'extern' ;
FALSE     : 'false' ;
FN        : 'fn' ;
FOR       : 'for' ;
GET       : 'get' ;
IF        : 'if' ;
IMPL      : 'impl' ;
IN        : 'in' ;
IS        : 'is' ;
LET       : 'let' ;
LOOP      : 'loop' ;
MATCH     : 'match' ;
MUT       : 'mut' ;
NAMEOF    : 'nameof' ;
NULL      : 'null' ;
OVERRIDE  : 'override' ;
PROT      : 'prot' ;
PUB       : 'pub' ;
RETURN    : 'return' ;
SELF_TYPE : 'Self' ;
SELF      : 'self' ;
STATIC    : 'static' ;
STRUCT    : 'struct' ;
SUPER     : 'super' ;
TRAIT     : 'trait' ;
TRUE      : 'true' ;
TYPE      : 'type' ;
TYPEOF    : 'typeof' ;
UNSAFE    : 'unsafe' ;
USE       : 'use' ;
VIRTUAL   : 'virtual' ;
WHILE     : 'while' ;

UNDERSCORE : '_' ;
IDENTIFIER : [A-Za-z_] [A-Za-z0-9_]* ;

// ---- Numeric literals ----

FLOAT_LITERAL
    : ( DEC '.' DEC EXPONENT? FLOAT_SUFFIX?
      | DEC EXPONENT FLOAT_SUFFIX?
      | DEC FLOAT_SUFFIX
      ) { lastType != DOT }?
    ;

INT_LITERAL
    : ( DEC
      | '0x' '_'* HEX_DIGIT [0-9A-Fa-f_]*
      | '0o' '_'* [0-7] [0-7_]*
      | '0b' '_'* [01] [01_]*
      ) INT_SUFFIX?
    ;

// A literal run into identifier characters: `10u64`, `1.0abc`, `0x`, `0b12`, `5_u7`.
// Valid literals are the same length or shorter, so they win whenever they match.
INVALID_NUMBER
    : [0-9] [A-Za-z0-9_]*
    | DEC '.' [0-9] [A-Za-z0-9_]* { lastType != DOT }?
    ;

fragment DEC          : [0-9] [0-9_]* ;
fragment HEX_DIGIT    : [0-9A-Fa-f] ;
fragment EXPONENT     : [eE] [+-]? '_'* [0-9] [0-9_]* ;
fragment INT_SUFFIX   : '_' ( 'i8' | 'i16' | 'i32' | 'i64' | 'isize' | 'u8' | 'u16' | 'u32' | 'u64' | 'usize' ) ;
fragment FLOAT_SUFFIX : '_' ( 'f32' | 'f64' ) ;

// ---- Char and string literals ----

CHAR_LITERAL   : '\'' ( ~['\\\r\n] | ESCAPE ) '\'' ;
STRING_LITERAL : '"' ( ~["\\\r\n] | ESCAPE )* '"' ;
// Multi-line string: ends at the first unescaped `"""`; `"` and `""` may appear inside.
TRIPLE_STRING_LITERAL
    : '"""' ( ~["\\] | ESCAPE | '"' ( ~["\\] | ESCAPE ) | '""' ( ~["\\] | ESCAPE ) )* '"""'
    ;

// Lenient twins of the rules above: any escape, optional closing quote. They only win
// when the strict rule fails (bad escape, unterminated, empty or multi-char `'..'`).
INVALID_CHAR_LITERAL : '\'' ( ~['\\\r\n] | LINE_ESCAPE )* '\''? ;
INVALID_STRING
    : '"' ( ~["\\\r\n] | LINE_ESCAPE )* '"'?
    | '"""' ( ~["\\] | ANY_ESCAPE | '"' ( ~["\\] | ANY_ESCAPE ) | '""' ( ~["\\] | ANY_ESCAPE ) )* '"""'?
    ;

fragment ESCAPE      : '\\' ( [nrt0\\"'] | 'u{' HEX_DIGIT (HEX_DIGIT (HEX_DIGIT (HEX_DIGIT (HEX_DIGIT HEX_DIGIT?)?)?)?)? '}' ) ;
fragment LINE_ESCAPE : '\\' ~[\r\n] ;
fragment ANY_ESCAPE  : '\\' . ;

FSTRING_START        : 'f"' -> pushMode(FSTRING) ;
FSTRING_TRIPLE_START : 'f"""' -> type(FSTRING_START), pushMode(FSTRING_TRIPLE) ;

// ---- Operators and punctuation (longest match wins) ----

AS_Q        : 'as?' ;
ELLIPSIS    : '...' ;
DOTDOT_EQ   : '..=' ;
DOTDOT      : '..' ;
DOT         : '.' ;
COLON_COLON : '::' ;
COLON       : ':' ;
COMMA       : ',' ;
SEMI        : ';' ;
AT          : '@' ;
QQ          : '??' ;
QUESTION    : '?' ;
FAT_ARROW   : '=>' ;
ARROW       : '->' ;

EQ_EQ       : '==' ;
NOT_EQ      : '!=' ;
LE          : '<=' ;
SHL_EQ      : '<<=' ;
SHL         : '<<' ;
LT          : '<' ;
GT          : '>' ;
AND_AND     : '&&' ;
OR_OR       : '||' ;
BANG        : '!' ;

ASSIGN      : '=' ;
PLUS_EQ     : '+=' ;
MINUS_EQ    : '-=' ;
STAR_EQ     : '*=' ;
SLASH_EQ    : '/=' ;
PERCENT_EQ  : '%=' ;
AMP_EQ      : '&=' ;
PIPE_EQ     : '|=' ;
CARET_EQ    : '^=' ;

PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
SLASH       : '/' ;
PERCENT     : '%' ;
AMP         : '&' ;
PIPE        : '|' ;
CARET       : '^' ;
TILDE       : '~' ;

LPAREN      : '(' ;
RPAREN      : ')' ;
LBRACKET    : '[' ;
RBRACKET    : ']' ;
LBRACE      : '{' { openBrace(); } ;
// Becomes INTERP_END (and leaves the interpolation) when it closes an f-string `{`.
RBRACE      : '}' { closeBrace(); } ;

UNEXPECTED_CHAR : . ;

// ---- F-strings: f"text {expr} text" ----
// `{` pushes DEFAULT_MODE for the expression; RBRACE pops back here at brace depth 0.

mode FSTRING;

FSTRING_TEXT         : ( ~[{}"\\\r\n] | ESCAPE | '{{' | '}}' )+ ;
INTERP_START         : '{' { openInterpolation(); } -> pushMode(DEFAULT_MODE) ;
FSTRING_END          : '"' -> popMode ;
// Bad escape or lone `}`.
INVALID_FSTRING      : ( FSTRING_BAD_ESCAPE | '}' ) ;
// A single-line f-string may not contain a raw line break: report it and leave the f-string.
UNTERMINATED_FSTRING : ( '\r'? '\n' | '\r' ) -> popMode ;

fragment FSTRING_BAD_ESCAPE : '\\' ( 'u{' ~[}"\r\n]* '}'? | ~[\r\n] )? ;

mode FSTRING_TRIPLE;

FSTRING_TRIPLE_TEXT    : ( ~[{}"\\] | ESCAPE | '{{' | '}}' )+ -> type(FSTRING_TEXT) ;
// `"` and `""` are text; `"""` (longer) ends the string.
FSTRING_TRIPLE_QUOTES  : ( '"' | '""' ) -> type(FSTRING_TEXT) ;
FSTRING_TRIPLE_INTERP  : '{' { openInterpolation(); } -> type(INTERP_START), pushMode(DEFAULT_MODE) ;
FSTRING_TRIPLE_END     : '"""' -> type(FSTRING_END), popMode ;
FSTRING_TRIPLE_INVALID : ( FSTRING_BAD_ESCAPE | '}' ) -> type(INVALID_FSTRING) ;
