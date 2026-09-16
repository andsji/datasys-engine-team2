package dk.itu.datasys;

public class StorageEngine {
    /** All persistent state (catalog + data files) lives under this directory. */
    public StorageEngine(Path dataDirectory) { 
        /* ... */ 
    }

    public void createTable(String tableName, List<ColumnSpec> columns) throws IllegalArgumentException{ 
        /* ... */ 
    }
    
    public void copyFile(String tableName, String csvFilePath) { 
        /* ... */ 
    }
    
    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) { 
        /* ... */ 
    }
}
