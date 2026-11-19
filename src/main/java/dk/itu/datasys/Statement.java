package dk.itu.datasys;

import java.util.*;

import dk.itu.datasys.Specifications.*;
import dk.itu.datasys.Statement.*;

public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement, DeleteStatement {

public static record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement { }

public static record CopyStatement(String tableName, String csvFilePath)
        implements Statement { }

public static record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement { }

public static record Predicate(String columnName, Comparison comparison, Object constant) { }

public static record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement { }
}