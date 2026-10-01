package dk.itu.datasys.operators;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Specifications.ColumnSpec;
import dk.itu.datasys.Specifications.ColumnType;
import dk.itu.datasys.Statement.Predicate;

public final class FilterOperator implements Operator {

	private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

	private final Operator child;
	private final Predicate predicate;
	private final List<ColumnSpec> schema;
	private final int columnIndex;
	private int rowsIn;
	private int rowsOut;

	public FilterOperator(Operator child, Predicate predicate) {
		this.child = Objects.requireNonNull(child, "child");
		this.predicate = Objects.requireNonNull(predicate, "predicate");
		this.schema = child.schema();
		this.columnIndex = findColumnIndex(schema, predicate.columnName());
		if (columnIndex < 0) {
			throw new IllegalArgumentException("Unknown column: " + predicate.columnName());
		}
		validateConstant(schema.get(columnIndex).type(), predicate.constant());
	}

	@Override
	public List<ColumnSpec> schema() {
		return schema;
	}

	@Override
	public void open() {
		rowsIn = 0;
		rowsOut = 0;
		child.open();
	}

	@Override
	public Object[] next() {
		Object[] row;
		while ((row = child.next()) != null) {
			rowsIn++;
			if (matches(row[columnIndex])) {
				rowsOut++;
				return row;
			}
		}
		return null;
	}

	@Override
	public void close() {
		child.close();
		LOGGER.debug("predicate={} rowsIn={} rowsOut={}", predicate, rowsIn, rowsOut);
	}

	private boolean matches(Object value) {
		int comparison = compare(schema.get(columnIndex).type(), value, predicate.constant());
		return switch (predicate.comparison()) {
			case EQUALS -> comparison == 0;
			case LESS_THAN -> comparison < 0;
			case GREATER_THAN -> comparison > 0;
		};
	}

	private int compare(ColumnType type, Object left, Object right) {
		return switch (type) {
			case STRING -> ((String) left).compareTo((String) right);
			case LONG -> Long.compare((Long) left, (Long) right);
			case DOUBLE -> Double.compare((Double) left, (Double) right);
		};
	}

	private int findColumnIndex(List<ColumnSpec> columns, String columnName) {
		for (int index = 0; index < columns.size(); index++) {
			if (columns.get(index).name().equals(columnName)) {
				return index;
			}
		}
		return -1;
	}

	private void validateConstant(ColumnType type, Object constant) {
		boolean valid = switch (type) {
			case STRING -> constant instanceof String;
			case LONG -> constant instanceof Long;
			case DOUBLE -> constant instanceof Double;
		};
		if (!valid) {
			throw new IllegalArgumentException("Constant type does not match column type " + type);
		}
	}
}
