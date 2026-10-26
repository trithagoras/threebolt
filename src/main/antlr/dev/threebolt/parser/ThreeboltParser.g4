// Placeholder parser: Phase 2 of docs/PLAN.md replaces this with the full grammar.
parser grammar ThreeboltParser;

options { tokenVocab = ThreeboltLexer; }

compilationUnit : .*? EOF ;
