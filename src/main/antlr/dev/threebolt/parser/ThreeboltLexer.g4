// Placeholder lexer: Phase 1 of docs/PLAN.md replaces this with the full spec §1 lexer.
lexer grammar ThreeboltLexer;

LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' (BLOCK_COMMENT | .)*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;

IDENTIFIER    : [A-Za-z_] [A-Za-z0-9_]* ;
INT_LITERAL   : [0-9] [0-9_]* ;
OTHER         : . ;
