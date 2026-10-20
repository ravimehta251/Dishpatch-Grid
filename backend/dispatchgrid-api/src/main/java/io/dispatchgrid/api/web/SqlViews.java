package io.dispatchgrid.api.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Stable camelCase JSON projections; persistence entities and secrets never escape. */
@Component
public class SqlViews implements RowMapper<Map<String, Object>> {
  private final ObjectMapper mapper;

  public SqlViews(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
      String label = rs.getMetaData().getColumnLabel(i);
      Object value = rs.getObject(i);
      if (value instanceof Timestamp ts) value = ts.toInstant();
      if (label.equals("payload") && value != null) {
        try {
          value = mapper.readTree(value.toString());
        } catch (JsonProcessingException ex) {
          throw new SQLException("Invalid stored JSON", ex);
        }
      }
      if (value instanceof java.sql.Array array) value = array.getArray();
      StringBuilder key = new StringBuilder();
      boolean upper = false;
      for (char ch : label.toCharArray()) {
        if (ch == '_') upper = true;
        else {
          key.append(upper ? Character.toUpperCase(ch) : ch);
          upper = false;
        }
      }
      result.put(key.toString(), value);
    }
    return result;
  }
}
