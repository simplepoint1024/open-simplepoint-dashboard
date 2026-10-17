grammar Calculation;
options { caseInsensitive = true; }

formula: EQ? comparison EOF;
comparison: concatenation ((EQ | NE | LT | LE | GT | GE) concatenation)*;
concatenation: addition (AMP addition)*;
addition: multiplication ((PLUS | MINUS) multiplication)*;
multiplication: power ((STAR | SLASH) power)*;
power: percentage (CARET percentage)*;
percentage: unary PERCENT*;
unary: (PLUS | MINUS)* atom;
atom: NUMBER | STRING | TRUE | FALSE | NULL | FIELD
    | IDENTIFIER LP (comparison (COMMA comparison)*)? RP
    | LP comparison RP;
EQ: '=';
NE: '<>';
LE: '<=';
GE: '>=';
LT: '<';
GT: '>';
AMP: '&';
PLUS: '+';
MINUS: '-';
STAR: '*';
SLASH: '/';
CARET: '^';
PERCENT: '%';
LP: '(';
RP: ')';
COMMA: ',';
TRUE: 'TRUE';
FALSE: 'FALSE';
NULL: 'NULL';
NUMBER: ([0-9]+ ('.' [0-9]*)? | '.' [0-9]+) ('E' [+-]? [0-9]+)?;
STRING: '"' ('""' | ~["\r\n])* '"';
FIELD: '[' (']]' | ~[\]\r\n])+ ']';
IDENTIFIER: [A-Z_] [A-Z_0-9.]*;
WS: [ \t\r\n]+ -> skip;
