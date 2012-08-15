// CloudCoder - a web-based pedagogical programming environment
// Copyright (C) 2011-2012, Jaime Spacco <jspacco@knox.edu>
// Copyright (C) 2011-2012, David H. Hovemeyer <david.hovemeyer@gmail.com>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.cloudcoder.app.server.persist;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.cloudcoder.app.shared.model.ModelObjectField;
import org.cloudcoder.app.shared.model.ModelObjectIndexType;
import org.cloudcoder.app.shared.model.ModelObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC utility methods.
 * 
 * @author David Hovemeyer
 */
public class DBUtil {
    private static final Logger logger=LoggerFactory.getLogger(DBUtil.class);
    
    /**
     * Quietly close a {@link PreparedStatement}.
     * 
     * @param stmt the PreparedStatement to close
     */
	public static void closeQuietly(PreparedStatement stmt) {
		try {
			if (stmt != null) {
				stmt.close();
			}
		} catch (SQLException e) {
		    logger.error("Unable to close prepared statement",e);
		}
	}

	/**
	 * Quietly close a {@link ResultSet}.
	 * 
	 * @param resultSet the ResultSet to close
	 */
	public static void closeQuietly(ResultSet resultSet) {
		try {
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e) {
		    logger.error("Unable to close result set",e);
		}
	}
	
	/**
	 * Get placeholders for an insert statement.
	 * 
	 * @param schema the schema for the object being inserted
	 * @return placeholders for an insert statement
	 */
	public static String getInsertPlaceholders(ModelObjectSchema<?> schema) {
		StringBuilder buf = new StringBuilder();
		
		for (int i = 0; i < schema.getNumFields(); i++) {
			if (buf.length() > 0) {
				buf.append(", ");
			}
			buf.append("?");
		}
		
		return buf.toString();
	}

	/**
	 * Get insert placeholders for all fields except the unique id.
	 * For the unique id field, a literal NULL value will be specified.
	 * This is useful for generating an insert statement where the unique
	 * id is an autoincrement column.
	 * 
	 * @param schema the schema of a model object
	 * @return insert placeholders for all fields except the unique id
	 */
	public static<E> String getInsertPlaceholdersNoId(ModelObjectSchema<E> schema) {
		StringBuilder buf = new StringBuilder();
		
		for (ModelObjectField<? super E, ?> field : schema.getFieldList()) {
			if (buf.length() > 0) {
				buf.append(", ");
			}
			buf.append(field.isUniqueId() ? "NULL" : "?");
		}
		
		return buf.toString();
	}
	
	/**
	 * Get placeholders for an update statement where all fields
	 * will be updated.
	 * 
	 * @return placeholders for an update statement where all fields will be updated
	 */
	public static<E> String getUpdatePlaceholders(ModelObjectSchema<E> schema) {
		return doGetUpdatePlaceholders(schema, true);
	}
	
	/**
	 * Get placeholders for an update statement where all fields except for
	 * the unique id field will be updated.
	 * 
	 * @return placeholders for an update statement where all fields except the
	 *         unique id field will be update
	 */
	public static<E> String getUpdatePlaceholdersNoId(ModelObjectSchema<E> schema) {
		return doGetUpdatePlaceholders(schema, false);
	}

	private static<E> String doGetUpdatePlaceholders(ModelObjectSchema<E> schema, boolean includeUniqueId) {
		StringBuilder buf = new StringBuilder();
		
		for (ModelObjectField<? super E, ?> field : schema.getFieldList()) {
			if (!field.isUniqueId() || includeUniqueId) {
				if (buf.length() > 0) {
					buf.append(", ");
				}
				buf.append(field.getName());
				buf.append(" = ?");
			}
		}
		
		return buf.toString();
	}
	
	/**
	 * Get a CREATE TABLE statement for creating a table with the given schema and name.
	 * 
	 * @param schema     the table's schema
	 * @param tableName  the name of the table
	 * @return the text of the CREATE TABLE statement
	 */
	public static<E> String getCreateTableStatement(ModelObjectSchema<E> schema, String tableName) {
		StringBuilder sql = new StringBuilder();
		
		sql.append("CREATE TABLE `");
		sql.append(tableName);
		sql.append("` (");
		
		int count = 0;
		
		// Field descriptors
		for (ModelObjectField<? super E, ?> field : schema.getFieldList()) {
			if (count > 0) {
				sql.append(",");
			}
			sql.append("\n  `");
			sql.append(field.getName());
			sql.append("` ");
			sql.append(getSQLDatatype(field));
			
			sql.append(field.isAllowNull() ? " NULL" : " NOT NULL");
			
			if (field.getIndexType() == ModelObjectIndexType.IDENTITY) {
				sql.append(" AUTO_INCREMENT");
			}
			
			count++;
		}
		
		// Keys
		for (ModelObjectField<? super E, ?> field : schema.getFieldList()) {
			if (field.getIndexType() == ModelObjectIndexType.NONE) {
				continue;
			}
			
			if (count > 0) {
				sql.append(",");
			}
			sql.append("\n  ");
			
			switch (field.getIndexType()) {
			case IDENTITY:
				sql.append("PRIMARY KEY (`");
				sql.append(field.getName());
				sql.append("`)");
				break;
				
			case UNIQUE:
			case NON_UNIQUE:
				sql.append(field.getIndexType() == ModelObjectIndexType.UNIQUE ? "UNIQUE " : "");
				sql.append("KEY `");
				sql.append(field.getName());
				sql.append("` (`");
				sql.append(field.getName());
				sql.append("`)");
				break;
			}
		}
		
		sql.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8");
		
		return sql.toString();
	}

	private static Object getSQLDatatype(ModelObjectField<?,?> field) {
		if (field.getType() == String.class) {
			// If the field length is Integer.MAX_VALUE, make it a text field.
			// Otherwise, make it VARCHAR.
			return field.getSize() == Integer.MAX_VALUE ? "text" : ("varchar(" + field.getSize() + ")");
		} else if (field.getType() == Short.class) {
			return "mediumint(9)";
		} else if (field.getType() == Integer.class) {
			return "int(11)";
		} else if (field.getType() == Long.class) {
			return "bigint(20)";
		} else if (field.getType() == Boolean.class) {
			return "tinyint(1)";
		} else if (Enum.class.isAssignableFrom(field.getType())) {
			// Enumeration values are represented as integers (their ordinal values)
			// in the database
			return "int(11)";
		} else {
			throw new IllegalArgumentException("Unknown field type: " + field.getType().getName());
		}
	}

	/**
	 * Execute a literal SQL statement.
	 * There is no provision for binding parameters, getting results, etc.
	 * 
	 * @param conn  the Connection to use to execute the statement
	 * @param sql   the statement
	 * @throws SQLException if an error occurs
	 */
	public static void execSql(Connection conn, String sql) throws SQLException {
		PreparedStatement stmt = null;
		try {
			stmt  = conn.prepareStatement(sql);
			stmt.execute();
		} finally {
			closeQuietly(stmt);
		}
	}

	/**
	 * Connect to the database server without connecting to a specific
	 * database on that server.
	 * 
	 * @param configProperties CloudCoder configuration properties
	 * @param prefix           the prefix for database-related configuration properties (e.g., "cloudcoder.db")
	 * @return the Connection to the database server
	 * @throws SQLException 
	 */
	public static Connection connectToDatabaseServer(Properties config, String prefix) throws SQLException {
		return doConnectToDatabaseServer(config, prefix, "");
	}

	/**
	 * Connect to the CloudCoder database.
	 * 
	 * @param config the CloudCoder configuration properties
	 * @param prefix           the prefix for database-related configuration properties (e.g., "cloudcoder.db")
	 * @return the Connection to the CloudCoder database
	 * @throws SQLException 
	 */
	public static Connection connectToDatabase(Properties config, String prefix) throws SQLException {
		return doConnectToDatabaseServer(config, prefix, getProp(config, prefix + ".databaseName"));
	}

	private static Connection doConnectToDatabaseServer(Properties config, String prefix, String databaseName) throws SQLException {
		String dbUser = getProp(config, prefix + ".user");
		String dbPasswd = getProp(config, prefix + ".passwd");
		String dbHost = getProp(config, prefix + ".host");

		String portStr = "";
		if (config.getProperty(prefix + ".portStr") != null) {
			portStr = config.getProperty(prefix + ".portStr");
		}

		String url="jdbc:mysql://" + dbHost + portStr+ "/" + databaseName + "?user=" + dbUser + "&password=" + dbPasswd;
		return DriverManager.getConnection(url);
	}
	
	private static String getProp(Properties properties, String propName) {
		String value = properties.getProperty(propName);
		if (value == null) {
			throw new IllegalArgumentException("configuration property " + propName + " is not defined");
		}
		return value;
	}

	/**
	 * Create a database table.
	 * 
	 * @param conn       the Connection to the database
	 * @param tableName  the table name
	 * @param schema     the {@link ModelObjectSchema} describing the type of object to be stored in the table
	 * @throws SQLException
	 */
	public static<E> void createTable(Connection conn, String tableName, ModelObjectSchema<E> schema) throws SQLException {
		String sql = getCreateTableStatement(schema, tableName);
		execSql(conn, sql);
	}

	/**
	 * Store an arbitrary bean in the database.
	 * 
	 * @param conn      the Connection to the database
	 * @param bean      the bean (model object) to store in the database
	 * @param schema    the {@link ModelObjectSchema} for the bean
	 * @param tableName the database table to store the bean in
	 */
	public static<E> void storeBean(Connection conn, E bean, ModelObjectSchema<E> schema, String tableName) throws SQLException {
		StringBuilder buf = new StringBuilder();
		
		buf.append("insert into " + tableName);
		buf.append(" values (");
		buf.append(getInsertPlaceholdersNoId(schema));
		buf.append(")");
		
		PreparedStatement stmt = null;
		ResultSet genKeys = null;
		
		try {
			stmt = conn.prepareStatement(buf.toString(), schema.hasUniqueId() ? PreparedStatement.RETURN_GENERATED_KEYS : 0);
			
			// Now for the magic: iterate through the schema fields
			// and bind the query parameters based on the bean properties.
			int index = 1;
			for (ModelObjectField<? super E, ?> field : schema.getFieldList()) {
				if (field.isUniqueId()) {
					continue;
				}
				try {
					Object value = field.get(bean);
					if (value instanceof Enum) {
						// Enum values are converted to integers
						value = Integer.valueOf(((Enum<?>)value).ordinal());
					}
					stmt.setObject(index++, value);
				} catch (Exception e) {
					throw new SQLException(
							"Couldn't get property " + field.getPropertyName() +
							" of " + bean.getClass().getName() + " object");
				}
			}
			
			// Execute the insert
			stmt.executeUpdate();
			
			if (schema.hasUniqueId()) {
				genKeys = stmt.getGeneratedKeys();
				if (!genKeys.next()) {
					throw new SQLException("Couldn't get generated id for " + bean.getClass().getName()); 
				}
				int id = genKeys.getInt(1);
				
				// Set the unique id value in the bean
				try {
					schema.getUniqueIdField().setUntyped(bean, (Integer)id);
				} catch (Exception e) {
					e.printStackTrace();
					throw new SQLException("Couldn't set generated unique id for " + bean.getClass().getName(), e);
				}
			}
		} finally {
			closeQuietly(genKeys);
			closeQuietly(stmt);
		}
	}

	/**
	 * Attempt to load the CloudCoder configuration properties;
	 * either from an embedded "cloudcoder.properties" resource
	 * (which would be the case in production) or from the filesystem
	 * (for development).
	 * 
	 * @return the CloudCoder configuration properties
	 * @throws IOException
	 */
	public static Properties getConfigProperties() throws IOException {
		Properties properties = new Properties();
	
		// See if we can load "cloudcoder.properties" as an embedded resource.
		URL u = CreateWebappDatabase.class.getClassLoader().getResource("cloudcoder.properties");
		if (u != null) {
			InputStream in = u.openStream();
			try {
				properties.load(in);
			} finally {
				in.close();
			}
		} else {
			System.out.println("Warning: loading cloudcoder.properties from filesystem");
			properties.load(new FileReader("../cloudcoder.properties"));
		}
		
		return properties;
	}

	/**
	 * Create a database.
	 * 
	 * @param conn    Connection to the database server
	 * @param dbName  name of the database to create
	 * @throws SQLException
	 */
	public static void createDatabase(Connection conn, String dbName) throws SQLException {
		execSql(
				conn,
				"create database " + dbName +
				" character set 'utf8' " +
				" collate 'utf8_general_ci' ");
	}

	/**
	 * Convert given value to given type.
	 * We use this to massage values returned by JDBC so that they
	 * are correct for storing in a model object field.
	 * 
	 * @param value the value to convert
	 * @param type  the type to convert the value to
	 * @return the converted value
	 */
	public static Object convertValue(Object value, Class<?> type) {
		// Easy case: value is correct type already
		if (value.getClass() == type) {
			return value;
		}
		
		if (type.isEnum()) {
			// value must be an Integer
			return type.getEnumConstants()[(Integer)value];
		} else if (type == Boolean.class) {
			// value must be some kind of integer
			if (value instanceof Number) {
				Number n = (Number) value;
				return n.intValue() == 0 ? Boolean.FALSE : Boolean.TRUE;
			}
		}
		
		throw new IllegalArgumentException("Unsupported conversion from " + value.getClass().getName() + " to " + type.getName());
	}
}
