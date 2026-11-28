package dk.itu.datasys.operators;

import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.StorageEngine;
import dk.itu.datasys.StorageEngine.TableDefinition;
import dk.itu.datasys.Catalog.Partition;
import dk.itu.datasys.Specifications.ColumnSpec;

public final class ScanOperator implements Operator{
    
    private final StorageEngine storageEngine;
    private final String tableName;
    private final TableDefinition table;
    private final List<Partition> partitions;
    private int rowsOut;
    private int index;
    private Iterator<Object[]> rows;
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    public ScanOperator(StorageEngine storageEngine, String tableName, TableDefinition table, List<Partition> partitions){
        this.storageEngine = storageEngine;
        this.tableName = tableName;
        this.table = table;
        this.partitions = partitions;
    }
    
    public ScanOperator(StorageEngine storageEngine, String tableName, List<Partition> partitions) {
        this(storageEngine, tableName, storageEngine.tableDefinition(tableName), partitions);
    }

    @Override
    public List<ColumnSpec> schema() {
        return List.copyOf(table.columns);
    }

    @Override 
    public void open(){
        rowsOut = 0;
        index = 0;
        rows = List.<Object[]>of().iterator();
    }

    @Override 
    public Object[] next(){
        while(!rows.hasNext() && index < partitions.size()){
            Partition partition = partitions.get(index);
            index++;
            List<Object[]> partitionRows = storageEngine.readPartitionRows(table, partition);
            rows = partitionRows.iterator();
        }

        if (!rows.hasNext()) {
            return null;
        }
        rowsOut++;
        return rows.next();
    }

    @Override 
    public void close(){
        LOGGER.debug("table={} partitions={} rowsOut={}",
                tableName, partitions.size(), rowsOut);
    }
}
