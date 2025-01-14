/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

grammar TrainDBSql;

@lexer::header {
    import org.apache.calcite.avatica.util.Casing;
    import org.apache.calcite.sql.parser.SqlParser;
}

@lexer::members {
    SqlParser.Config parserConfig;

    public TrainDBSqlLexer(CharStream input, SqlParser.Config parserConfig) {
        this(input);
        this.parserConfig = parserConfig;
    }
}

traindbStmts
    : createModeltype
    | dropModeltype
    | trainModel
    | dropModel
    | alterModel
    | createSynopsis
    | dropSynopsis
    | alterSynopsis
    | showStmt
    | useSchema
    | describeTable
    | bypassDdlStmt
    | deleteQueryLogs
    | deleteTasks
    | exportModel
    | importModel
    | exportSynopsis
    | importSynopsis
    | analyzeSynopsis
    | incrementalParallelQuery
    | incrementalQuery
    ;

createModeltype
    : K_CREATE K_MODELTYPE modeltypeName K_FOR modeltypeCategory K_AS modeltypeSpecClause
    ;

dropModeltype
    : K_DROP K_MODELTYPE modeltypeName
    ;

trainModel
    : K_TRAIN K_MODEL modelName trainModeltypeClause trainDataClause
      trainSampleClause? trainModelOptionsClause?
    ;

trainModeltypeClause
    : K_MODELTYPE modeltypeName
    | K_UPDATE exModelName
    | K_LIKE exModelName
    ;

trainDataClause
    : ( K_FROM | K_ON ) tableName '(' columnNameList ')' joinTableListOpt* tableConditionListClause?
    | tableConditionListClause?
    ;

dropModel
    : K_DROP K_MODEL modelName
    ;

alterModel
    : K_ALTER K_MODEL modelName alterModelClause
    ;

alterModelClause
    : K_RENAME K_TO newModelName
    | enableDisableClause
    ;

enableDisableClause
    : K_ENABLE
    | K_DISABLE
    ;

deleteQueryLogs
    : K_DELETE K_QUERYLOGS limitNumber
    ;

deleteTasks
    : K_DELETE K_TASKS limitNumber
    ;

modeltypeName
    : IDENTIFIER
    ;

modeltypeCategory
    : K_INFERENCE
    | K_SYNOPSIS
    ;

modeltypeSpecClause
    : modeltypeLocation K_CLASS modeltypeClassName K_IN modeltypeUri
    ;

modeltypeLocation
    : ( K_LOCAL )?
    | K_REMOTE
    ;

modeltypeClassName
    : STRING_LITERAL
    ;

modeltypeUri
    : STRING_LITERAL
    ;

joinTableListOpt
    : K_JOIN tableName '(' columnNameList ')'
    ;

tableConditionListClause
    : K_ON tableConditionList
    ;

tableConditionList
    : tableConditionOr (K_AND tableConditionOr)*
    ;

tableConditionOr
    : tableCondtionNot (K_OR tableCondtionNot)*
    ;

tableCondtionNot
    : K_NOT? predicate
    ;

trainSampleClause
    : K_SAMPLE samplePercent K_PERCENT
    ;

samplePercent
    : NUMERIC_LITERAL
    ;

trainModelOptionsClause
    : K_OPTIONS '(' optionKeyValueList ')'
    ;

optionKeyValueList
    : optionKeyValue ( ',' optionKeyValue )*
    ;

optionKeyValue
    : optionKey '=' optionValue
    ;

optionKey
    : STRING_LITERAL
    ;

optionValue
    : STRING_LITERAL
    | NUMERIC_LITERAL
    ;

exportModel
    : K_EXPORT K_MODEL modelName exportToClause?
    ;

importModel
    : K_IMPORT K_MODEL modelName K_FROM ( modelBinaryString | K_FILE filenameString )?
    ;

modelBinaryString
    : BINARY_STRING_LITERAL
    ;

exportSynopsis
    : K_EXPORT K_SYNOPSIS synopsisName exportToClause?
    ;

exportToClause
    : K_TO K_FILE filenameString
    ;

importSynopsis
    : K_IMPORT K_SYNOPSIS synopsisName synopsisTypeClause? K_FROM ( synopsisBinaryString | K_FILE filenameString )?
    ;

analyzeSynopsis
    : K_ANALYZE K_SYNOPSIS synopsisName
    ;

filenameString
    : STRING_LITERAL
    ;

synopsisBinaryString
    : BINARY_STRING_LITERAL
    ;

showStmt
    : K_SHOW showTargets showWhereClause?
    ;

showTargets
    : K_MODELTYPES
    | K_MODELS
    | K_SYNOPSES
    | K_SCHEMAS
    | K_TABLES
    | K_COLUMNS
    | K_HYPERPARAMETERS
    | K_TRAININGS
    | K_QUERYLOGS
    | K_TASKS
    | K_PARTITIONS
    ;

showWhereClause
    : K_WHERE showWhereExpressionList
    ;

showWhereExpressionList
    : showWhereExpression ( K_AND showWhereExpression )*
    ;

showWhereExpression
    : showFilterKey showFilterOperator optionValue
    ;

showFilterKey
    : IDENTIFIER
    ;

showFilterOperator
    : K_LIKE
    | '='
    ;

modelName
    : IDENTIFIER
    ;

exModelName
    : IDENTIFIER
    ;

newModelName
    : IDENTIFIER
    ;

createSynopsis
    : K_CREATE K_SYNOPSIS synopsisName synopsisTypeClause? K_FROM K_MODEL modelName K_LIMIT limitSizeClause
    ;

synopsisTypeClause
    : K_AS ( K_TABLE | K_FILE | K_DEFAULT )
    ;

limitSizeClause
    : limitRows ( K_ROWS )?
    | limitPercent K_PERCENT
    ;

dropSynopsis
    : K_DROP K_SYNOPSIS synopsisName
    ;

alterSynopsis
    : K_ALTER K_SYNOPSIS synopsisName alterSynopsisClause
    ;

alterSynopsisClause
    : K_RENAME K_TO newSynopsisName
    | enableDisableClause
    ;

useSchema
    : K_USE schemaName
    ;

describeTable
    : ( K_DESCRIBE | K_DESC ) tableName
    ;

bypassDdlStmt
    : K_BYPASS ddlString
    ;

incrementalQuery
    : K_INCREMENTAL ddlString
    ;

incrementalParallelQuery
    : K_INCREMENTAL K_PARALLEL ddlString
    ;

schemaName
    : IDENTIFIER
    ;

tableName
    : ( schemaName '.' )? tableIdentifier=IDENTIFIER
    ;

columnNameList
    : columnName ( ',' columnName )*
    ;

columnName
    : IDENTIFIER
    ;

synopsisName
    : IDENTIFIER
    ;

newSynopsisName
    : IDENTIFIER
    ;

limitRows
    : NUMERIC_LITERAL
    ;

limitPercent
    : NUMERIC_LITERAL
    ;

limitNumber
    : NUMERIC_LITERAL
    ;

ddlString
    : ( . | WHITESPACES )+?
    ;

comparisonOperator
    : '=' | '>' | '<' | '<' '=' | '>' '=' | '<' '>' | '!' '=' | '!' '>' | '!' '<'
    ;

predicate
    : '(' tableConditionList ')'
    | expression comparisonOperator expression
    | expression K_NOT? (K_LIKE | K_RLIKE) expression (K_ESCAPE expression)?
    | expression K_IS nullConstant
    ;

expression
    : K_NULL
    | STRING_LITERAL
    | NUMERIC_LITERAL
    | K_TRUE
    | K_FALSE
    | fullColumnName
    | '(' expression ')'
    | expression op=('*' | '/' | '%') expression
    | op=('+' | '-') expression
    | expression op=('+' | '-' | '&' | '^' | '|' | '#' | '||' | '<<' | '>>'  ) expression
    | expression comparisonOperator expression
    | K_NOT expression
    | expression K_IS nullConstant
    | date
    ;

fullColumnName
    : (tableName '.')? columnName
    ;

nullConstant
    : K_NOT? K_NULL
    ;

date
    : K_DATE constant
    ;

constant
    : STRING_LITERAL
    | BINARY_STRING_LITERAL
    | NUMERIC_LITERAL
    ;

error
    : UNEXPECTED_CHAR
        {
            throw new RuntimeException("UNEXPECTED_CHAR=" + $UNEXPECTED_CHAR.text);
        }
    ;

K_ALTER : A L T E R ;
K_ANALYZE : A N A L Y Z E ;
K_AND : A N D ;
K_AS : A S ;
K_BYPASS : B Y P A S S ;
K_CLASS : C L A S S ;
K_COLUMNS : C O L U M N S ;
K_CREATE : C R E A T E ;
K_DEFAULT : D E F A U L T ;
K_DELETE : D E L E T E ;
K_DESC : D E S C ;
K_DESCRIBE : D E S C R I B E ;
K_DISABLE : D I S A B L E ;
K_DROP : D R O P ;
K_ENABLE : E N A B L E ;
K_ESCAPE : E S C A P E ;
K_EXPORT : E X P O R T ;
K_FALSE : F A L S E ;
K_FILE : F I L E ;
K_FOR : F O R ;
K_FROM : F R O M ;
K_HYPERPARAMETERS : H Y P E R P A R A M E T E R S ;
K_IMPORT : I M P O R T ;
K_IN : I N ;
K_INCREMENTAL : I N C R E M E N T A L ;
K_INFERENCE : I N F E R E N C E ;
K_IS : I S ;
K_JOIN : J O I N ;
K_LIKE : L I K E ;
K_RLIKE : R L I K E ;
K_LIMIT : L I M I T ;
K_LOCAL : L O C A L ;
K_MODEL : M O D E L ;
K_MODELS : M O D E L S ;
K_MODELTYPE : M O D E L T Y P E ;
K_MODELTYPES : M O D E L T Y P E S ;
K_NOT : N O T ;
K_NULL : N U L L ;
K_ON : O N ;
K_OPTIONS : O P T I O N S ;
K_OR : O R ;
K_SAMPLE : S A M P L E ;
K_PERCENT : P E R C E N T ;
K_PARTITIONS : P A R T I T I O N S ;
K_PARALLEL : P A R A L L E L ;
K_QUERYLOGS : Q U E R Y L O G S ;
K_REMOTE : R E M O T E ;
K_RENAME : R E N A M E ;
K_ROWS : R O W S ;
K_SCHEMAS : S C H E M A S ;
K_SHOW : S H O W ;
K_SYNOPSES : S Y N O P S E S ;
K_SYNOPSIS : S Y N O P S I S ;
K_TABLE : T A B L E ;
K_TABLES : T A B L E S ;
K_TASKS : T A S K S ;
K_TO : T O ;
K_TRAIN : T R A I N ;
K_TRAININGS : T R A I N I N G S;
K_TRUE : T R U E ;
K_UPDATE : U P D A T E ;
K_USE : U S E ;
K_WHERE : W H E R E ;

IDENTIFIER
    : '"' ( ~["\r\n] | '""' )* '"'
        {
            setText(getText().substring(1, getText().length() - 1).replace("\"\"", "\""));
        }
    | '`' ( ~[`\r\n] | '``' )* '`'
        {
            setText(getText().substring(1, getText().length() - 1).replace("``", "`"));
        }
    | '[' ( ~[\]\r\n]* | ']]' )* ']'
        {
            setText(getText().substring(1, getText().length() - 1).replace("]]", "]"));
        }
    | LETTER ( LETTER | DIGIT )*
        {
            setText(TrainDBSql.toCase(getText(), parserConfig.unquotedCasing()));
        }
    ;

NUMERIC_LITERAL
    : DIGIT+ ( '.' DIGIT* )? ( E [-+]? DIGIT+ )?
    | '.' DIGIT+ ( E [-+]? DIGIT+ )?
    ;

STRING_LITERAL
    : '\'' ( ~['\r\n] | '\'\'' )* '\''
        {
            setText(getText().substring(1, getText().length() - 1).replace("''", "'"));
        }
    ;

BINARY_STRING_LITERAL
    : 'x' '\'' HEXDIGIT* '\''
        {
            setText(getText().substring(2, getText().length() - 1));
        }
    ;

HEXDIGIT
    : [a-fA-F0-9]
    ;

WHITESPACES : [ \t\r\n]+ -> channel(HIDDEN) ;

UNEXPECTED_CHAR : . ;

fragment A : [aA] ;
fragment B : [bB] ;
fragment C : [cC] ;
fragment D : [dD] ;
fragment E : [eE] ;
fragment F : [fF] ;
fragment G : [gG] ;
fragment H : [hH] ;
fragment I : [iI] ;
fragment J : [jJ] ;
fragment K : [kK] ;
fragment L : [lL] ;
fragment M : [mM] ;
fragment N : [nN] ;
fragment O : [oO] ;
fragment P : [pP] ;
fragment Q : [qQ] ;
fragment R : [rR] ;
fragment S : [sS] ;
fragment T : [tT] ;
fragment U : [uU] ;
fragment V : [vV] ;
fragment W : [wW] ;
fragment X : [xX] ;
fragment Y : [yY] ;
fragment Z : [zZ] ;

fragment LETTER : [a-zA-Z_] ;
fragment DIGIT : [0-9] ;
