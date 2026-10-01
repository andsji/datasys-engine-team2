package dk.itu.datasys.operators;

import java.util.List;

import dk.itu.datasys.Specifications.ColumnSpec;

public interface Operator {
    List<ColumnSpec> schema();
    void open();
    Object[] next();
    void close();
}
