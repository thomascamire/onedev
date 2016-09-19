package com.pmease.commons.hibernate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.inject.Named;
import javax.persistence.ManyToOne;

import org.apache.commons.io.IOUtils;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.dom4j.io.XPP3Reader;
import org.hibernate.Criteria;
import org.hibernate.Interceptor;
import org.hibernate.Query;
import org.hibernate.ReplicationMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.pmease.commons.hibernate.dao.Dao;
import com.pmease.commons.hibernate.migration.MigrationHelper;
import com.pmease.commons.hibernate.migration.Migrator;
import com.pmease.commons.hibernate.migration.VersionTable;
import com.pmease.commons.hibernate.migration.VersionedDocument;
import com.pmease.commons.util.BeanUtils;
import com.pmease.commons.util.ClassUtils;
import com.pmease.commons.util.FileUtils;

@Singleton
public class DefaultPersistManager implements PersistManager {

	private static final Logger logger = LoggerFactory.getLogger(DefaultPersistManager.class);
	
	protected final Set<ModelProvider> modelProviders;

	protected final PhysicalNamingStrategy physicalNamingStrategy;

	protected final Properties properties;
	
	protected final Migrator migrator;
	
	protected final Interceptor interceptor;
	
	protected final IdManager idManager;
	
	protected final Dao dao;
	
	protected final StandardServiceRegistry serviceRegistry;
	
	protected volatile SessionFactory sessionFactory;
	
	@Inject
	public DefaultPersistManager(Set<ModelProvider> modelProviders, PhysicalNamingStrategy physicalNamingStrategy,
			@Named("hibernate") Properties properties, Migrator migrator, Interceptor interceptor, 
			IdManager idManager, Dao dao) {
		this.modelProviders = modelProviders;
		this.physicalNamingStrategy = physicalNamingStrategy;
		this.properties = properties;
		this.migrator = migrator;
		this.interceptor = interceptor;
		this.idManager = idManager;
		this.dao = dao;
		serviceRegistry = new StandardServiceRegistryBuilder().applySettings(properties).build();
	}
	
	private boolean isMySQL() {
		return properties.getProperty(PropertyNames.DIALECT).toLowerCase().contains("mysql");
	}

	private boolean isHSQL() {
		return properties.getProperty(PropertyNames.DIALECT).toLowerCase().contains("hsql");
	}
	
	protected void execute(List<String> sqls, boolean failOnError) {
    	Connection conn = null;
		Statement stmt = null;
		try {
			conn = getConnection();
			stmt = conn.createStatement();
			for (String sql: sqls) {
				logger.debug("Executing sql: " + sql);
				if (failOnError) {
					stmt.execute(sql);
				} else {
					try {
						stmt.execute(sql);
					} catch (Exception e) {
						logger.error("Error executing sql", e);
					}
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException e) {
				}
			}
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException e) {
				}
			}
		}
	}
	
	protected Metadata buildMetadata() {
		MetadataSources metadataSources = new MetadataSources(serviceRegistry);
		for (Class<? extends AbstractEntity> each: ClassUtils.findImplementations(AbstractEntity.class, AbstractEntity.class)) {
			metadataSources.addAnnotatedClass(each);
		}
		
		for (ModelProvider provider: modelProviders) {
			for (Class<? extends AbstractEntity> modelClass: provider.getModelClasses())
				metadataSources.addAnnotatedClass(modelClass);
		}
		
		return metadataSources.getMetadataBuilder().applyPhysicalNamingStrategy(physicalNamingStrategy).build();
	}
	
	protected SessionFactory buildSessionFactory(Metadata metadata) {
    	return metadata.getSessionFactoryBuilder().applyInterceptor(interceptor).build();
	}
	
	@Override
	public void start() {
		String dbDataVersion = readDbDataVersion();
		
		logger.info("Checking data version...");
		String appDataVersion = MigrationHelper.getVersion(migrator.getClass());
		if (dbDataVersion != null && !dbDataVersion.equals(appDataVersion)) {
			throw new RuntimeException("Data version mismatch "
					+ "(database data version: "+ dbDataVersion + ", application data version: "+ appDataVersion + "). "
					+ "Please upgrade the database.");
		}
		
		Metadata metadata = buildMetadata();
		
		if (dbDataVersion == null) {
			File tempFile = null;
        	try {
            	tempFile = File.createTempFile("schema", ".sql");
	        	new SchemaExport().setOutputFile(tempFile.getAbsolutePath())
	        			.setFormat(false).createOnly(EnumSet.of(TargetType.SCRIPT), metadata);
	        	List<String> sqls = new ArrayList<String>();
	        	for (String sql: FileUtils.readLines(tempFile)) {
	        		if (shouldInclude(sql))
	        			sqls.add(sql);
	        	}
	        	execute(sqls, true);
        	} catch (IOException e) {
        		throw new RuntimeException(e);
        	} finally {
        		if (tempFile != null)
        			tempFile.delete();
        	}

        	sessionFactory = buildSessionFactory(metadata);

    		idManager.init();
			
			Session session = sessionFactory.openSession();
			Transaction transaction = session.beginTransaction();
			try {
				VersionTable dataVersion = new VersionTable();
				dataVersion.versionColumn = appDataVersion;
				session.save(dataVersion);
				session.flush();
				transaction.commit();
			} catch (Exception e) {
				transaction.rollback();
			} finally {
				session.close();
			}
		} else {
			sessionFactory = metadata.getSessionFactoryBuilder().applyInterceptor(interceptor).build();
    		idManager.init();
		}
	}

	@Override
	public void stop() {
		if (sessionFactory != null) {
			sessionFactory.close();
			sessionFactory = null;
		}
	}

	protected Connection getConnection() {
		try {
			Class.forName(properties.getProperty(PropertyNames.DRIVER_PROPNAME));
	    	Connection conn = DriverManager.getConnection(
	    			properties.getProperty(PropertyNames.URL_PROPNAME), 
	    			properties.getProperty(PropertyNames.USER_PROPNAME), 
	    			properties.getProperty(PropertyNames.PASSWORD_PROPNAME));
	    	return conn;
		} catch (Exception e) {
			throw Throwables.propagate(e);
		}
	}

	private String getVersionTableName() {
		JdbcEnvironment environment = serviceRegistry.getService(JdbcEnvironment.class);
		Identifier identifier = Identifier.toIdentifier(VersionTable.class.getSimpleName());
		return physicalNamingStrategy.toPhysicalTableName(identifier, environment).getText();
	}
	
	private String getVersionFieldName() {
		return VersionTable.class.getFields()[0].getName();
	}
	
	private String getVersionColumnName() {
		JdbcEnvironment environment = serviceRegistry.getService(JdbcEnvironment.class);
		Identifier identifier = Identifier.toIdentifier(getVersionFieldName());
		return physicalNamingStrategy.toPhysicalColumnName(identifier, environment).getText();
	}
	
	protected String readDbDataVersion() {
		try (Connection conn = getConnection()) {
			conn.setAutoCommit(false);
			String versionTableName = getVersionTableName();
			boolean versionTableExists = false;
			try (ResultSet resultset = conn.getMetaData().getTables(null, null, versionTableName.toUpperCase(), null)) {
				if (resultset.next())
					versionTableExists = true;
			}
			if (!versionTableExists) {
				try (ResultSet resultset = conn.getMetaData().getTables(null, null, versionTableName.toLowerCase(), null)) {
					if (resultset.next()) {
						versionTableExists = true;
					}
				}
			}
			if (!versionTableExists) {
				return null;
			} else {
				try (	Statement stmt = conn.createStatement();
						ResultSet resultset = stmt.executeQuery("select " + getVersionColumnName() + " from " + versionTableName)) {
					if (!resultset.next())
						throw new RuntimeException("No data version found in database: this is normally caused "
								+ "by unsuccessful restore/upgrade, please clean the database and try again");
					String dataVersion = resultset.getString(1);
					if (resultset.next())
						throw new RuntimeException("Illegal data version format in database");
					return dataVersion;
				}
			}
		} catch (Throwable e) {
			if (e.getMessage() != null && e.getMessage().contains("ORA-00942")) 
				return null;
			else
				throw Throwables.propagate(e);
		}
	}
	
	@Override
	public void migrate(File dataDir) {
		File versionFile = new File(dataDir, VersionTable.class.getSimpleName() + "s.xml");
		VersionedDocument dom = readFile(versionFile);
		List<Element> elements = dom.getRootElement().elements();
		if (elements.size() != 1)
			throw new RuntimeException("Incorrect data format: illegal data version");
		Element versionElement = elements.iterator().next().element(getVersionFieldName());		
		if (versionElement == null) {
			throw new RuntimeException("Incorrect data format: no data version");
		}
		
		if (MigrationHelper.migrate(versionElement.getText(), migrator, dataDir)) {
			versionElement.setText(MigrationHelper.getVersion(migrator.getClass()));
			writeFile(versionFile, dom, false);
		}		
	}

	private void writeFile(File file, VersionedDocument dom, boolean pretty) {
		OutputStream os = null;
		try {
			os = new FileOutputStream(file);
			OutputFormat format = new OutputFormat();
			format.setIndent(pretty);
			format.setNewlines(pretty);
			format.setEncoding(Charsets.UTF_8.name());
			XMLWriter writer = new XMLWriter(os, format);
			writer.write(dom);
		} catch (Exception e) {
			throw Throwables.propagate(e);
		} finally {
			IOUtils.closeQuietly(os);
		}
	}
	
	private VersionedDocument readFile(File file) {
		try {
			char[] chars = FileUtils.readFileToString(file, Charsets.UTF_8.name()).toCharArray();
			return new VersionedDocument(new XPP3Reader().read(chars));
		} catch (Exception e) {
			throw Throwables.propagate(e);
		}
	}
	
	/**
	 * Determines whether or not entityType1 has transitive foreign key
	 * dependency on entityType2.
	 * 
	 * @param entityType1
	 * @param entityType2
	 * @return
	 */
	private boolean hasForeignKeyDependency(Class<?> entityType1, Class<?> entityType2) {
		for (Field field: BeanUtils.getFields(entityType1)) {
			if (field.getAnnotation(ManyToOne.class) != null) {
				if (field.getType() == entityType2)
					return true;
				if (field.getType() != entityType1 && 
						hasForeignKeyDependency(field.getType(), entityType2)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * @return hibernate entity types ordered using foreign key dependency
	 *         information. For example, Build type comes before Configuration
	 *         type as it has Build has foreign key reference to Configuration.
	 */
	private List<Class<?>> getEntityTypes(SessionFactory sessionFactory) {
		List<Class<?>> entityTypes = new ArrayList<>();
		for (Iterator<String> it = sessionFactory.getAllClassMetadata().keySet().iterator(); it.hasNext();) {
			String entityTypeName = (String) it.next();
			try {
				entityTypes.add(Class.forName(entityTypeName));
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
		}
		
		/* Collections.sort does not work here */
		List<Class<?>> sorted = new ArrayList<Class<?>>();
		while (!entityTypes.isEmpty()) {
			Class<?> dependencyLeaf = null;
			for (Class<?> entityType: entityTypes) {
				boolean hasDependents = false;
				for (Class<?> each: entityTypes) {
					if (each != entityType) {
						if (hasForeignKeyDependency(each, entityType)) {
							hasDependents = true;
							break;
						}
					}
				}
				if (!hasDependents) {
					dependencyLeaf = entityType;
					break;
				}
			}
			if (dependencyLeaf != null) {
				sorted.add(dependencyLeaf);
				entityTypes.remove(dependencyLeaf);
			} else {
				throw new RuntimeException("Looped foreigh key dependency found between model classes");
			}
		}
		return sorted;
	}
	
	@Sessional
	@Override
	public void exportData(File exportDir, int batchSize) {
		Session session = sessionFactory.openSession();
		for (Class<?> entityType: getEntityTypes(sessionFactory)) {
			logger.info("Exporting table '" + entityType.getSimpleName() + "'...");
			
			logger.info("Querying table ids...");
			
			Criteria criteria = session.createCriteria(entityType, "entity")
					.setProjection(Projections.property("entity.id")).addOrder(Order.asc("id"));
			@SuppressWarnings("unchecked")
			List<Long> ids = criteria.list();
			int count = ids.size();
			
			for (int i=0; i<count/batchSize; i++) {
				exportEntity(session, entityType, ids, i*batchSize, batchSize, batchSize, exportDir);
				// clear session to free memory
				session.clear();
			}
			
			if (count%batchSize != 0) {
				exportEntity(session, entityType, ids, count/batchSize*batchSize, count%batchSize, batchSize, exportDir);
			}
			logger.info("");
		}
	}

	private void exportEntity(Session session, Class<?> entityType, List<Long> ids, int start, int count, int batchSize, File exportDir) {
		logger.info("Loading table rows ({}->{}) from database...", String.valueOf(start+1), (start + count));
		
		Query query = session.createQuery("from " + entityType.getSimpleName() + " where id>=:fromId and id<=:toId");
		query.setParameter("fromId", ids.get(start));
		query.setParameter("toId", ids.get(start+count-1));
		
		logger.info("Converting table rows to XML...");
		VersionedDocument dom = new VersionedDocument();
		Element rootElement = dom.addElement("list");
		for (Object entity: query.list())
			rootElement.appendContent(VersionedDocument.fromBean(entity));
		String fileName;

		if (start == 0)
			fileName = entityType.getSimpleName() + "s.xml";
		else
			fileName = entityType.getSimpleName() + "s.xml." + (start/batchSize + 1);
		
		logger.info("Writing resulting XML to file '" + fileName + "...");
		writeFile(new File(exportDir, fileName), dom, true);
	}

	/*
	 * We do not use @Transactional annotation and will manage the session and transaction manually 
	 * in this method to reduce memory usage if importing a large database.
	 */
	@Sessional
	@Override
	public void importData(Metadata metadata, File dataDir) {
		Session session = dao.getSession();
		// check version
		File versionFile = new File(dataDir, VersionTable.class.getSimpleName() + "s.xml");
		VersionedDocument dom = readFile(versionFile);
		List<Element> elements = dom.getRootElement().elements();
		if (elements.size() != 1)
			throw new RuntimeException("Incorrect data format: illegal data version");
		Element versionElement = elements.iterator().next().element(getVersionFieldName());		
		if (versionElement == null) {
			throw new RuntimeException("Incorrect data format: no data version");
		}
		
		if (!versionElement.getText().equals(MigrationHelper.getVersion(migrator.getClass()))) {
			throw new RuntimeException("Data version mismatch");
		}
		
		logger.info("Clearing database...");
		dropAll(metadata);
		
		logger.info("Creating tables...");
		createTables(metadata);
        	
		List<Class<?>> entityTypes = getEntityTypes(sessionFactory);
		Collections.reverse(entityTypes);
		for (Class<?> entityType: entityTypes) {
			File[] dataFiles = dataDir.listFiles(new FilenameFilter() {

				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith(entityType.getSimpleName() + "s.xml");
				}
				
			});
			for (File file: dataFiles) {
				try {
					logger.info("Importing from data file '" + file.getName() + "'...");
					session.beginTransaction();
					dom = readFile(file);
					for (Element element: dom.getRootElement().elements()) {
						element.detach();
						AbstractEntity entity = (AbstractEntity) new VersionedDocument(DocumentHelper.createDocument(element)).toBean();
						session.replicate(entity, ReplicationMode.EXCEPTION);
					}
					session.flush();
					session.clear();
					session.getTransaction().commit();
				} catch (Throwable e) {
					session.getTransaction().rollback();
					throw Throwables.propagate(e);
				}
			}
		}	

		logger.info("Applying foreign key constraints...");
		
		applyForeignKeyConstraints(metadata);
	}

	@Override
	public void applyForeignKeyConstraints(Metadata metadata) {
		File tempFile = null;
    	try {
        	tempFile = File.createTempFile("schema", ".sql");
        	new SchemaExport().setOutputFile(tempFile.getAbsolutePath())
        			.setFormat(false).createOnly(EnumSet.of(TargetType.SCRIPT), metadata);
        	List<String> sqls = new ArrayList<>();
        	for (String sql: FileUtils.readLines(tempFile)) {
        		if (isApplyingForeignKeyConstraints(sql)) {
        			sqls.add(sql);
        		}
        	}
        	execute(sqls, true);
    	} catch (IOException e) {
    		throw new RuntimeException(e);
    	} finally {
    		if (tempFile != null)
    			tempFile.delete();
    	}
	}
	
	private void createTables(Metadata metadata) {
		File tempFile = null;
    	try {
        	tempFile = File.createTempFile("schema", ".sql");
        	new SchemaExport().setOutputFile(tempFile.getAbsolutePath())
        			.setFormat(false).createOnly(EnumSet.of(TargetType.SCRIPT), metadata);
        	List<String> sqls = new ArrayList<>();
        	for (String sql: FileUtils.readLines(tempFile)) {
        		if (shouldInclude(sql) && !isApplyingForeignKeyConstraints(sql))
        			sqls.add(sql);
        	}
        	execute(sqls, true);
    	} catch (IOException e) {
    		throw new RuntimeException(e);
    	} finally {
    		if (tempFile != null)
    			FileUtils.deleteFile(tempFile);
    	}
	}
	
	@Override
	public void dropForeignKeyConstraints(Metadata metadata) {
		File tempFile = null;
    	try {
        	tempFile = File.createTempFile("schema", ".sql");
        	new SchemaExport().setOutputFile(tempFile.getAbsolutePath())
        			.setFormat(false).drop(EnumSet.of(TargetType.SCRIPT), metadata);
        	List<String> sqls = new ArrayList<>();
        	for (String sql: FileUtils.readLines(tempFile)) {
        		if (isDroppingForeignKeyConstraints(sql))
        			sqls.add(sql);
        	}
        	execute(sqls, false);
    	} catch (IOException e) {
    		throw new RuntimeException(e);
    	} finally {
    		if (tempFile != null)
    			tempFile.delete();
    	}
	}
	
	private void dropAll(Metadata metadata) {
		File tempFile = null;
    	try {
        	tempFile = File.createTempFile("schema", ".sql");
        	new SchemaExport().setOutputFile(tempFile.getAbsolutePath())
        			.setFormat(false).drop(EnumSet.of(TargetType.SCRIPT), metadata);
        	List<String> sqls = new ArrayList<>();
        	for (String sql: FileUtils.readLines(tempFile)) {
        		sqls.add(sql);
        	}
        	execute(sqls, false);
    	} catch (IOException e) {
    		throw new RuntimeException(e);
    	} finally {
    		if (tempFile != null)
    			tempFile.delete();
    	}
	}
	
	private boolean isApplyingForeignKeyConstraints(String sql) {
		return sql.toLowerCase().contains(" foreign key ");
	}

	private boolean isDroppingForeignKeyConstraints(String sql) {
		return sql.toLowerCase().contains(" drop constraint ");
	}
	
	private boolean shouldInclude(String sql) {
		// some databases will create index on foreign keys automatically, so 
		// we skip creating indexes for those
		if (!sql.toLowerCase().startsWith("create index") || !sql.toLowerCase().endsWith("_id)")) {
			return true;
		} else {
			return !isMySQL() && !isHSQL();
		}
	}

	@Override
	public SessionFactory getSessionFactory() {
		return sessionFactory;
	}
	
}
