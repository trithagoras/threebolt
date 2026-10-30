// Threebolt parser — spec §2–§11, grammar notes §17.
//
// Lexer hand-offs (see ThreeboltLexer.g4):
//  - `>` is always one GT token. `>=`, `>>` and `>>=` are adjacent GTs/ASSIGN, checked by
//    `adjacent()`, so type-argument lists never need `>>` splitting.
//  - `get` is an IDENTIFIER; `getKw` recognises it before `fn` in member declarations only.
//  - `select` is an IDENTIFIER too; `atSelect` recognises it before `{` where an initializer
//    could start (so not in an `if`/`while`/`for`/`match` head, §7.14).
//
// Two pieces of parser state steer prediction through semantic predicates:
//  - `typeArgsAhead()` (§7.5): in expressions, `<` after a name starts type arguments iff they
//    parse as `typeArgs` and are followed by `(` or `::`.
//  - `isPatternAhead()` (§17.18): whether a pattern or a type follows `is`.
//  - `structOk()` (§6.3): initializers (`Name {...}`, `{...}`) are not allowed directly in an
//    `if`/`while`/`for`/`match` head. `noStructExpr` pushes "forbidden"; every bracketing
//    construct pushes "allowed" again.
parser grammar ThreeboltParser;

options { tokenVocab = ThreeboltLexer; }

@parser::members {
    private final java.util.ArrayDeque<Boolean> structAllowed = new java.util.ArrayDeque<>();

    private void pushStruct(boolean allowed) { structAllowed.push(allowed); }

    private void popStruct() { structAllowed.pop(); }

    /** Whether an initializer may start here (§6.3). */
    protected boolean structOk() { return structAllowed.isEmpty() || structAllowed.peek(); }

    /** Inside an `if`/`while`/`for`/`match` head, outside any brackets (for error hints). */
    public boolean inNoStructHead() { return !structOk(); }

    /** The previous token ends exactly where the next one starts (`>` `>` → `>>`). */
    protected boolean adjacent() {
        Token prev = _input.LT(-1), next = _input.LT(1);
        return prev != null && prev.getStopIndex() + 1 == next.getStartIndex();
    }

    /**
     * §17.18: whether a pattern rather than a type follows `is`: `[`, `(`, a path followed by `{`
     * (only where initializers are allowed, §6.3), or an optionally negated literal followed by
     * `..`/`..=`.
     */
    protected boolean isPatternAhead() {
        int t = _input.LA(1);
        if (t == LBRACKET || t == LPAREN) return true;
        int i = 1;
        if (t == MINUS) t = _input.LA(++i);
        if (t == INT_LITERAL || t == FLOAT_LITERAL || t == STRING_LITERAL || t == TRIPLE_STRING_LITERAL
                || t == CHAR_LITERAL || t == TRUE || t == FALSE || t == NULL) {
            int next = _input.LA(i + 1);
            return next == DOTDOT || next == DOTDOT_EQ;
        }
        if (t != IDENTIFIER || i != 1 || !structOk()) return false;
        while (_input.LA(i + 1) == COLON_COLON && _input.LA(i + 2) == IDENTIFIER) i += 2;
        return _input.LA(i + 1) == LBRACE;
    }

    /** `select` directly before `{`, where an initializer could also start (§7.14). */
    protected boolean atSelect() {
        return _input.LT(1).getText().equals("select") && _input.LA(2) == LBRACE && structOk();
    }

    /** `get` directly before `fn` (§4.8). */
    protected boolean atGetFn() {
        return _input.LT(1).getText().equals("get") && _input.LA(2) == FN;
    }

    /**
     * §7.5: whether the `<` at LT(1) begins a type-argument list — the tokens up to the
     * matching `>` parse as `typeArgs` and the next token is `(` or `::`. Decided by a
     * speculative parse that leaves the input where it was.
     */
    protected boolean typeArgsAhead() {
        if (_input.LA(1) != LT) return false;
        int start = _input.index();
        var speculative = new ThreeboltParser(_input);
        speculative.removeErrorListeners();
        speculative.setErrorHandler(new BailErrorStrategy());
        try {
            speculative.typeArgs();
            int next = _input.LA(1);
            return next == LPAREN || next == COLON_COLON;
        } catch (org.antlr.v4.runtime.misc.ParseCancellationException | RecognitionException e) {
            return false;
        } finally {
            _input.seek(start);
        }
    }
}

// ---- Entry points ----

compilationUnit : item* EOF ;

// Fragments: used for spec snippets, tests and tools.
memberList : member* EOF ;
stmtList   : stmt* EOF ;
exprOnly   : expr EOF ;
typeOnly   : type EOF ;

// ---- Items (§2.1) ----

item : attribute* visibility? itemKind ;

itemKind
    : useDecl
    | fnDecl
    | structDecl
    | classDecl
    | traitDecl
    | implDecl
    | enumDecl
    | typeAliasDecl
    | constDecl
    | staticDecl
    | externBlock
    ;

attribute : AT IDENTIFIER (LPAREN (literal (COMMA literal)* COMMA?)? RPAREN)? ;

visibility : PUB | INTERNAL | PROT ;

// use a::b::C;  use a::b::{C, D as E};  use a::b as c;  use a::b::*;
useDecl : USE IDENTIFIER (COLON_COLON IDENTIFIER)* (AS IDENTIFIER | COLON_COLON useGroup | COLON_COLON STAR)? SEMI ;

useGroup : LBRACE useName (COMMA useName)* COMMA? RBRACE ;

useName : IDENTIFIER (AS IDENTIFIER)? ;

fnDecl : UNSAFE? FN fnSignature fnBody ;

fnSignature : IDENTIFIER typeParams? LPAREN paramList? RPAREN (ARROW type)? ;

paramList : param (COMMA param)* COMMA? ;

param
    : MUT? SELF                                    # selfParam
    | MUT? IDENTIFIER COLON type (ASSIGN expr)?    # namedParam
    | ELLIPSIS                                     # variadicParam
    ;

// `;` for abstract, required trait members and extern functions. In an `=>` body a `{`
// starts an initializer (§4.7), never a block (§7.1).
fnBody
    : block                    # blockBody
    | FAT_ARROW assignOrExpr SEMI   # exprBody
    | SEMI                     # noBody
    ;

structDecl : STRUCT IDENTIFIER typeParams? baseList? (memberBlock | SEMI) ;

classDecl : ABSTRACT? CLASS IDENTIFIER typeParams? baseList? memberBlock ;

traitDecl : TRAIT IDENTIFIER typeParams? (COLON typePath (PLUS typePath)*)? memberBlock ;

implDecl : IMPL typeParams? typePath FOR type memberBlock ;

baseList : COLON typePath (COMMA typePath)* ;

memberBlock : LBRACE member* RBRACE ;

enumDecl : ENUM IDENTIFIER (COLON type)? LBRACE (enumMember (COMMA enumMember)* COMMA?)? RBRACE ;

enumMember : IDENTIFIER (ASSIGN expr)? ;

typeAliasDecl : TYPE IDENTIFIER typeParams? ASSIGN type SEMI ;

constDecl : CONST IDENTIFIER COLON type ASSIGN expr SEMI ;

staticDecl : STATIC LET MUT? IDENTIFIER COLON type ASSIGN expr SEMI ;

externBlock : EXTERN STRING_LITERAL LBRACE externFn* RBRACE ;

externFn : attribute* visibility? FN fnSignature SEMI ;

// ---- Members (§4.5–§4.10) ----

member : attribute* visibility? memberKind ;

memberKind
    : LET IDENTIFIER COLON type (ASSIGN expr)? SEMI                            # fieldMember
    | staticDecl                                                               # staticMember
    | dispatchModifier? UNSAFE? getKw? FN fnSignature fnBody                   # fnMember
    ;

dispatchModifier : ABSTRACT | VIRTUAL | OVERRIDE ;

getKw : {atGetFn()}?<fail={"expected a member declaration"}> IDENTIFIER ;

// ---- Generics (§10) ----

typeParams : LT typeParam (COMMA typeParam)* COMMA? GT ;

typeParam : IDENTIFIER (COLON typePath (PLUS typePath)*)? ;

typeArgs : LT type (COMMA type)* COMMA? GT ;

// ---- Types (§3.2) ----
// `|` is union here; types appear only after `:`, `->`, `as`, `as?`, `is`, in `<...>` and in
// aliases (§17.5).

type : nonUnionType (PIPE nonUnionType)* ;

nonUnionType
    : typePath                                                     # namedType
    | literalType                                                  # literalTypeRef
    | LPAREN RPAREN                                                # unitType
    | LPAREN type RPAREN                                           # groupType
    | LPAREN type COMMA (type (COMMA type)* COMMA?)? RPAREN        # tupleType
    | LBRACKET type RBRACKET                                       # listType
    | LBRACKET type SEMI expr RBRACKET                             # arrayType
    | EXTERN? FN LPAREN (type (COMMA type)* COMMA?)? RPAREN (ARROW type)?   # fnType
    | BANG                                                         # neverType
    ;

typePath : typeName (COLON_COLON typeName)* typeArgs? ;

typeName : IDENTIFIER | SELF_TYPE ;

literalType
    : MINUS? (INT_LITERAL | FLOAT_LITERAL)
    | STRING_LITERAL
    | TRIPLE_STRING_LITERAL
    | CHAR_LITERAL
    | TRUE
    | FALSE
    | NULL
    ;

// ---- Statements (§6) ----

block
    @init { pushStruct(true); }
    : LBRACE stmt* RBRACE
    ;
    finally { popStruct(); }

stmt
    : LET pattern (COLON type)? ASSIGN expr SEMI                         # letStmt
    | block                                                              # blockStmt
    | ifStmt                                                             # ifStmtAlt
    | WHILE (LET MUT? IDENTIFIER ASSIGN)? noStructExpr block             # whileStmt
    | LOOP block                                                         # loopStmt
    | FOR pattern QUESTION? IN noStructExpr block                        # forStmt
    | matchExpr SEMI?                                                    # matchStmt
    | BREAK SEMI                                                         # breakStmt
    | CONTINUE SEMI                                                      # continueStmt
    | RETURN expr? SEMI                                                  # returnStmt
    | DEFER (block | {_input.LA(1) != LBRACE}? expr SEMI)                # deferStmt
    | UNSAFE block                                                       # unsafeStmt
    | assignTarget assignOp expr SEMI                                    # assignStmt
    | {_input.LA(1) != LBRACE}? expr SEMI                                # exprStmt
    ;

ifStmt : IF (LET MUT? IDENTIFIER ASSIGN)? noStructExpr block (ELSE (block | ifStmt))? ;

// A place (§5.1) — checked later — or `_` to discard.
assignTarget : UNDERSCORE | postfixExpr ;

assignOp
    : ASSIGN | PLUS_EQ | MINUS_EQ | STAR_EQ | SLASH_EQ | PERCENT_EQ
    | AMP_EQ | PIPE_EQ | CARET_EQ | SHL_EQ
    | GT {adjacent()}?<fail={"'>>=' must not contain spaces"}> GT {adjacent()}?<fail={"'>>=' must not contain spaces"}> ASSIGN
    ;

// An expression or, where §7.1 permits, an assignment (fn `=>` bodies, lambdas, match arms).
assignOrExpr : (assignTarget assignOp)? expr ;

// ---- Expressions (§7) ----
// One rule per precedence level of §7.2, loosest first.

noStructExpr
    @init { pushStruct(false); }
    : expr
    ;
    finally { popStruct(); }

expr
    : lambda                         # lambdaExpr
    | UNSAFE FAT_ARROW expr          # unsafeExpr
    | coalesceExpr (IF coalesceExpr ELSE expr)?   # conditionalExpr
    ;

lambda : lambdaParams FAT_ARROW lambdaBody ;

lambdaParams
    : MUT? IDENTIFIER
    | LPAREN (lambdaParam (COMMA lambdaParam)* COMMA?)? RPAREN
    ;

// Each parenthesized parameter is a pattern (§7.8); the checker requires it to be irrefutable.
lambdaParam : patternAtom (COLON type)? ;

// In a lambda body `{` starts a block (§7.1).
lambdaBody : block | {_input.LA(1) != LBRACE}? assignOrExpr ;

coalesceExpr : orExpr (QQ coalesceExpr)? ;

orExpr : orExpr OR_OR andExpr | andExpr ;

andExpr : andExpr AND_AND comparisonExpr | comparisonExpr ;

// Non-associative: `a < b < c` is a syntax error (§17.9). The operator is an optional suffix,
// not separate alternatives: choosing between alternatives that share the `rangeExpr` prefix
// would happen before `typeArgsAhead()` can be evaluated and resolve `f(a < b, c > (d))` as
// comparisons.
comparisonExpr : rangeExpr (compareOp rangeExpr | IS isTarget | IN rangeExpr)? ;

// §7.3, §17.18: after `is`, a pattern or a type, chosen by `isPatternAhead()`.
isTarget
    : {isPatternAhead()}? patternAtom                  # patternIsTarget
    | type                                             # typeIsTarget
    ;

compareOp
    : EQ_EQ | NOT_EQ | LT | LE
    | GT {adjacent()}?<fail={"'>=' must not contain spaces"}> ASSIGN
    | GT
    ;

rangeExpr : bitOrExpr ((DOTDOT | DOTDOT_EQ) bitOrExpr)? ;

bitOrExpr : bitOrExpr PIPE bitXorExpr | bitXorExpr ;

bitXorExpr : bitXorExpr CARET bitAndExpr | bitAndExpr ;

bitAndExpr : bitAndExpr AMP shiftExpr | shiftExpr ;

shiftExpr : shiftExpr shiftOp additiveExpr | additiveExpr ;

shiftOp : SHL | GT {adjacent()}?<fail={"'>>' must not contain spaces"}> GT ;

additiveExpr : additiveExpr (PLUS | MINUS) multiplicativeExpr | multiplicativeExpr ;

multiplicativeExpr : multiplicativeExpr (STAR | SLASH | PERCENT) castExpr | castExpr ;

// `x as T?` is `(x as T)?` (§17.6).
castExpr : castExpr (AS | AS_Q) type QUESTION? | unaryExpr ;

unaryExpr : (MINUS | BANG | TILDE) unaryExpr | postfixExpr ;

postfixExpr : primary postfixOp* ;

postfixOp
    : DOT memberName ({typeArgsAhead()}? typeArgs)?    # memberOp
    | callArgs                                          # callOp
    | LBRACKET nested RBRACKET                          # indexOp
    | LBRACKET sliceBounds RBRACKET                     # sliceOp
    | QUESTION                                          # propagateOp
    ;

// Open-ended ranges exist only inside index brackets (§7.6, §17.17); `xs[a..b]` is an `indexOp`
// whose index is an ordinary range.
sliceBounds
    @init { pushStruct(true); }
    : bitOrExpr DOTDOT
    | (DOTDOT | DOTDOT_EQ) bitOrExpr
    | DOTDOT
    ;
    finally { popStruct(); }

// Any keyword is a member name after `.`; integers are tuple fields (`t.0.1`).
memberName : IDENTIFIER | INT_LITERAL | keyword ;

callArgs
    @init { pushStruct(true); }
    : LPAREN (arg (COMMA arg)* COMMA?)? RPAREN
    ;
    finally { popStruct(); }

arg : (IDENTIFIER COLON)? expr ;

primary
    : literal                                               # literalExpr
    | fstring                                               # fstringExpr
    | SELF                                                  # selfExpr
    | SUPER                                                 # superExpr
    | {atSelect()}? IDENTIFIER selectBody                   # selectExpr
    | {structOk()}? path initializer                        # namedInitExpr
    | {structOk()}? initializer                             # targetInitExpr
    | collectionLiteral                                     # collectionExpr
    | path                                                  # pathExpr
    | LPAREN RPAREN                                         # unitExpr
    | LPAREN nested RPAREN                                  # parenExpr
    | LPAREN nested COMMA (nested (COMMA nested)* COMMA?)? RPAREN   # tupleExpr
    | matchExpr                                             # matchExprAlt
    | TYPEOF LPAREN type RPAREN                             # typeofExpr
    | NAMEOF LPAREN type RPAREN                             # nameofExpr
    ;

// An expression inside brackets, where initializers are allowed again.
nested
    @init { pushStruct(true); }
    : expr
    ;
    finally { popStruct(); }

// a::b::C, f<T>, Type<T>::f, Ptr<c::long>::null — generic args only per §7.5.
path : pathSegment (COLON_COLON pathMember)* ;

pathSegment : (IDENTIFIER | SELF_TYPE) ({typeArgsAhead()}? typeArgs)? ;

// Any keyword is a name after `::` (`Ptr<T>::null()`, §1).
pathMember : (IDENTIFIER | keyword) ({typeArgsAhead()}? typeArgs)? ;

// { field: e, field, ..spread } (§4.7)
initializer
    @init { pushStruct(true); }
    : LBRACE (initField (COMMA initField)* COMMA?)? RBRACE
    ;
    finally { popStruct(); }

initField
    : IDENTIFIER COLON expr   # namedField
    | IDENTIFIER              # shorthandField
    | DOTDOT expr             # spreadField
    ;

// [a, b]  [v; n]  [k: v]  [:]  []  [e for p in it if c]  [k: v for p in it] (§7.7)
collectionLiteral
    @init { pushStruct(true); }
    : LBRACKET RBRACKET                                              # emptyCollection
    | LBRACKET COLON RBRACKET                                        # emptyMap
    | LBRACKET expr SEMI expr RBRACKET                               # repeatCollection
    | LBRACKET mapEntry compClauses RBRACKET                         # mapComprehension
    | LBRACKET expr compClauses RBRACKET                             # listComprehension
    | LBRACKET mapEntry (COMMA mapEntry)* COMMA? RBRACKET            # mapCollection
    | LBRACKET expr (COMMA expr)* COMMA? RBRACKET                    # listCollection
    ;
    finally { popStruct(); }

mapEntry : expr COLON expr ;

// Iterables and filters stop below the conditional level (§17.19): `if` after an iterable starts
// its filter, and a filter can't swallow the next clause.
compClauses : FOR compClause (COMMA compClause)* ;

compClause : pattern IN coalesceExpr (IF coalesceExpr)? ;

matchExpr : MATCH noStructExpr matchBody ;

// Arms are comma-separated; a block arm needs no comma (§7.11).
matchBody
    @init { pushStruct(true); }
    : LBRACE matchArm* RBRACE
    ;
    finally { popStruct(); }

// Guards stop below the conditional level so a guard can't swallow the arm's `=>` as a lambda.
matchArm
    : pattern (IF coalesceExpr)? FAT_ARROW  // a `{` body is always a block (§7.1)
      ( block COMMA?
      | {_input.LA(1) != LBRACE}? assignOrExpr (COMMA | {_input.LA(1) == RBRACE}?)
      )
    ;

// select { let p = ch.recv() => ..., after(d) => ..., _ => ... } (§7.14). Arms are like match arms;
// a case stops below the conditional level, as a guard does, so its `=>` is not a lambda's.
selectBody
    @init { pushStruct(true); }
    : LBRACE selectArm* RBRACE
    ;
    finally { popStruct(); }

selectArm
    : selectCase FAT_ARROW
      ( block COMMA?
      | {_input.LA(1) != LBRACE}? assignOrExpr (COMMA | {_input.LA(1) == RBRACE}?)
      )
    ;

selectCase
    : LET pattern ASSIGN coalesceExpr   # selectLet
    | UNDERSCORE                        # selectDefault
    | coalesceExpr                      # selectOp
    ;

fstring
    @init { pushStruct(true); }
    : FSTRING_START fstringPart* FSTRING_END
    ;
    finally { popStruct(); }

fstringPart : FSTRING_TEXT | INTERP_START expr INTERP_END ;

literal
    : INT_LITERAL
    | FLOAT_LITERAL
    | STRING_LITERAL
    | TRIPLE_STRING_LITERAL
    | CHAR_LITERAL
    | TRUE
    | FALSE
    | NULL
    ;

keyword
    : ABSTRACT | AS | BREAK | CLASS | CONST | CONTINUE | DEFER | ELSE | ENUM | EXTERN | FALSE
    | FN | FOR | IF | IMPL | IN | INTERNAL | IS | LET | LOOP | MATCH | MUT | NAMEOF | NULL | OVERRIDE
    | PROT | PUB | RETURN | SELF_TYPE | SELF | STATIC | STRUCT | SUPER | TRAIT | TRUE | TYPE
    | TYPEOF | UNSAFE | USE | VIRTUAL | WHILE
    ;

// ---- Patterns (§11) ----

pattern : patternAtom (PIPE patternAtom)* ;

patternAtom
    : UNDERSCORE                                                     # wildcardPattern
    | MUT? IDENTIFIER (IS type)?                                     # bindingPattern
    | IS type                                                        # isPattern
    | literalPattern ((DOTDOT | DOTDOT_EQ) literalPattern)?          # literalOrRangePattern
    | LPAREN RPAREN                                                  # unitPattern
    | LPAREN pattern RPAREN                                          # groupPattern
    | LPAREN pattern COMMA (pattern (COMMA pattern)* COMMA?)? RPAREN # tuplePattern
    | LBRACKET (listPatternElem (COMMA listPatternElem)* COMMA?)? RBRACKET   # listPattern
    | path LBRACE (fieldPattern (COMMA fieldPattern)* (COMMA DOTDOT)? COMMA? | DOTDOT)? RBRACE  # structPattern
    | pathSegment (COLON_COLON pathMember)+                          # pathPattern
    ;

listPatternElem : DOTDOT IDENTIFIER? | pattern ;

fieldPattern : MUT? IDENTIFIER | IDENTIFIER COLON pattern ;

literalPattern
    : MINUS? (INT_LITERAL | FLOAT_LITERAL)
    | STRING_LITERAL
    | TRIPLE_STRING_LITERAL
    | CHAR_LITERAL
    | TRUE
    | FALSE
    | NULL
    ;
