package org.remast.baralga.gui.model;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.PeriodFormat;
import org.remast.baralga.gui.BaralgaMain;
import org.remast.baralga.gui.settings.ApplicationSettings;
import org.remast.baralga.model.Project;
import org.remast.baralga.model.ProjectActivity;
import org.remast.baralga.model.filter.Filter;
import org.remast.util.TextResourceBundle;

/**
 * Reads and writes all objects to the database and maintains the database connection.
 * @author remast
 */
public class BaralgaDAO {

	/** The bundle for internationalized texts. */
	private static final TextResourceBundle textBundle = TextResourceBundle.getBundle(BaralgaMain.class);

	/** The logger. */
	private static final Log log = LogFactory.getLog(BaralgaDAO.class);

	/** The connection to the database. */
	private Connection connection;

	/** Statement to create table for the database version. */
	private static final String versionTableCreate =  
		"create table db_version (" + //$NON-NLS-1$
		"     id           identity," + //$NON-NLS-1$
		"     version      number," + //$NON-NLS-1$
		"     created_at   timestamp," + //$NON-NLS-1$
		"     description  varchar2(255)" + //$NON-NLS-1$
		"    )"; //$NON-NLS-1$
	
	/** Statement to insert the current database version. */
	private static final String versionTableInsert = "insert into db_version (version, description) values (1, 'Initial database setup.')"; //$NON-NLS-1$

	/** Statement to create table for the projects. */
	private static final String projectTableCreate =  
		"create table project (" + //$NON-NLS-1$
		"     id           identity," + //$NON-NLS-1$
		"     title        varchar(255)," + //$NON-NLS-1$
		"     description  varchar(4000)," + //$NON-NLS-1$
		"     active       boolean" + //$NON-NLS-1$
		"    )"; //$NON-NLS-1$

	/** Statement to create table for the activities. */
	private static final String activityTableCreate =  
		"create table activity (" + //$NON-NLS-1$
		"     id           identity," + //$NON-NLS-1$
		"     description  varchar(4000)," + //$NON-NLS-1$
		"     start        timestamp," + //$NON-NLS-1$
		"     end          timestamp," + //$NON-NLS-1$
		"     project_id   number," + //$NON-NLS-1$
		"     FOREIGN key (project_id) REFERENCES project(id)" + //$NON-NLS-1$
		"    )"; //$NON-NLS-1$

	/** The current version number of the database. */
	private int databaseVersion;

	/**
	 * Initializes the database and the connection to the database.
	 * @throws SQLException on error during initialization
	 */
	public void init() throws SQLException {
		final String dataDirName = ApplicationSettings.instance().getApplicationDataDirectory().getAbsolutePath();
		connection = DriverManager.getConnection("jdbc:h2:" + dataDirName + "/baralga;DB_CLOSE_ON_EXIT=FALSE", "baralga-user", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

		// Perform an update if necessary.
		updateDatabase();
	}

	/**
	 * Closes the database by closing the only connection to it.
	 */
	public void close() {
		try {
			if (connection != null && !connection.isClosed()) {
				connection.close();
			}
		} catch (SQLException e) {
			log.error(e, e);
		}
	}

	/**
	 * Updates or creates the database. If the database is empty it will be setup
	 * otherwise an existing database will be updated.
	 * @throws SQLException on error during update
	 */
	private void updateDatabase() throws SQLException {
		boolean databaseExists = false;

		// Look for table db_version if that is present the database has already been set up
		final Statement statement = connection.createStatement();
		ResultSet resultSet = statement.executeQuery("SHOW TABLES"); //$NON-NLS-1$
		while (resultSet.next()) {
			if ("db_version".equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) { //$NON-NLS-1$ //$NON-NLS-2$
				databaseExists = true;
				break;
			}
		}

		if (!databaseExists) {
			log.info("Creating Baralga DB."); //$NON-NLS-1$
			statement.execute(versionTableCreate);
			statement.execute(projectTableCreate);
			statement.execute(activityTableCreate);
			
			log.info("Inserting reference data."); //$NON-NLS-1$
			statement.execute(versionTableInsert);
			
			log.info("Baralga DB successfully created."); //$NON-NLS-1$
		}
		connection.commit();

		resultSet = statement.executeQuery("select max(version) as version, description from db_version"); //$NON-NLS-1$
		databaseVersion = -1;
		String description = "-"; //$NON-NLS-1$
		if (resultSet.next()) {
			databaseVersion = resultSet.getInt("version"); //$NON-NLS-1$
			description = resultSet.getString("description"); //$NON-NLS-1$
		}

		log.info("Using Baralga DB Version: " + databaseVersion + ", description: " + description); //$NON-NLS-1$ //$NON-NLS-2$
	}
	
	/**
	 * Removes a project.
	 * @param project the project to remove
	 */
	public void remove(final Project project) {
		if (project == null) {
			return;
		}

		try {
			// Remove activities associated with the project
			final PreparedStatement activityDelete = connection.prepareStatement("delete from activity where project_id = ?"); //$NON-NLS-1$
			activityDelete.setLong(1, project.getId());
			activityDelete.execute();

			// Remove the project
			final PreparedStatement projectDelete = connection.prepareStatement("delete from project where id = ?"); //$NON-NLS-1$
			projectDelete.setLong(1, project.getId());
			projectDelete.execute();
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

	/**
	 * Adds a new project.
	 * @param project the project to add
	 */
	public void addProject(final Project project) {
		if (project == null) {
			return;
		}

		// TODO: Check if exists
		try {
			final PreparedStatement pst = connection.prepareStatement("insert into project (title, description, active) values (?, ?, ?)"); //$NON-NLS-1$
			pst.setString(1, project.getTitle());
			pst.setString(2, project.getDescription());
			pst.setBoolean(3, project.isActive());

			pst.execute();

			final Statement st = connection.createStatement();
			final ResultSet rs = st.executeQuery("select max(id) as id from project"); //$NON-NLS-1$
			if (rs.next()) {
				long id = rs.getLong("id"); //$NON-NLS-1$
				project.setId(id);
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

	/**
	 * Getter for all active projects.
	 * @return read-only view of the projects
	 */
	public List<Project> getActiveProjects() {
		final List<Project> activeProjects = new ArrayList<Project>();

		try {
			final Statement st = connection.createStatement();

			final ResultSet rs = st.executeQuery("select * from project where active = True"); //$NON-NLS-1$
			while (rs.next()) {
				Project project = new Project(rs.getLong("id"), rs.getString("title"), rs.getString("description")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				project.setActive(true);
				activeProjects.add(project);
			}

		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}

		return Collections.unmodifiableList(activeProjects);
	}

	/**
	 * Getter for all projects (both active and inactive).
	 * @return read-only view of the projects
	 */
	public List<Project> getAllProjects() {
		final List<Project> allProjects = new ArrayList<Project>();

		try {
			final Statement statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery("select * from project"); //$NON-NLS-1$
			while (resultSet.next()) {
				final Project project = new Project(resultSet.getLong("id"), resultSet.getString("title"), resultSet.getString("description")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				project.setActive(resultSet.getBoolean("active")); //$NON-NLS-1$
				
				allProjects.add(project);
			}

		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}

		return Collections.unmodifiableList(allProjects);
	}

	/**
	 * Provides all activities.
	 * @return read-only view of the activities
	 */
	public List<ProjectActivity> getActivities() {
		final String filterCondition = StringUtils.EMPTY;
		return getActivities(filterCondition);
	}

	/**
	 * Provides all activities satisfying the given filter conditions.
	 * @param condition SQL condition for filtering
	 * @return read-only view of the activities
	 */
	public List<ProjectActivity> getActivities(final String condition) {
		final List<ProjectActivity> activities = new ArrayList<ProjectActivity>();

		try {
			final Statement statement = connection.createStatement();
			final String filterCondition = StringUtils.defaultString(condition);
			final ResultSet resultSet = statement.executeQuery("select * from activity, project where activity.project_id = project.id " + filterCondition + " order by start asc"); //$NON-NLS-1$ //$NON-NLS-2$
			while (resultSet.next()) {
				final Project project = new Project(resultSet.getLong("project.id"), resultSet.getString("title"), resultSet.getString("project.description")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				project.setActive(resultSet.getBoolean("active")); //$NON-NLS-1$

				final ProjectActivity activity = new ProjectActivity(new DateTime(resultSet.getTimestamp("start")), new DateTime(resultSet.getTimestamp("end")), project); //$NON-NLS-1$ //$NON-NLS-2$
				activity.setId(resultSet.getLong("activity.id")); //$NON-NLS-1$
				activity.setDescription(resultSet.getString("activity.description")); //$NON-NLS-1$
				
				activities.add(activity);
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}

		return activities;
	}

	/**
	 * Adds a new activity.
	 * @param activity the activity to add
	 */
	public void addActivity(final ProjectActivity activity) {
		if (activity == null) {
			return;
		}

		// TODO: Check if exists
		try {
			final PreparedStatement preparedStatement = connection.prepareStatement("insert into activity (description, start, end, project_id) values (?, ?, ?, ?)"); //$NON-NLS-1$
			
			preparedStatement.setString(1, activity.getDescription());

			final Timestamp d = new Timestamp( activity.getStart().getMillis());
			preparedStatement.setTimestamp(2,d);

			final Timestamp endDate = new Timestamp( activity.getEnd().getMillis());
			preparedStatement.setTimestamp(3, endDate);
			
			preparedStatement.setLong(4, activity.getProject().getId());
			
			preparedStatement.execute();

			final Statement statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery("select max(id) as id from activity"); //$NON-NLS-1$
			if (resultSet.next()) {
				long id = resultSet.getLong("id"); //$NON-NLS-1$
				activity.setId(id);
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

	/**
	 * Removes an activity.
	 * @param activity the activity to remove
	 */
	public void removeActivity(final ProjectActivity activity) {
		if (activity == null) {
			return;
		}

		try {
			final PreparedStatement preparedStatement = connection.prepareStatement("delete from activity where id = ?"); //$NON-NLS-1$
			preparedStatement.setLong(1, activity.getId());

			preparedStatement.execute();
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

	/**
	 * Adds a bunch of activities.
	 * @param activities the activities to add
	 */
	public void addActivities(final Collection<ProjectActivity> activities) {
		if (activities == null || activities.size() == 0) {
			return;
		}
		
		for (ProjectActivity activity : activities) {
			addActivity(activity);
		}
	}

	/**
	 * Removes a bunch of activities.
	 * @param activities the activities to remove
	 */
	public void removeActivities(final Collection<ProjectActivity> activities) {
		if (activities == null || activities.size() == 0) {
			return;
		}

		for (ProjectActivity activity : activities) {
			removeActivity(activity);
		}
	}

	/**
	 * Updates the project in the database. Pending changes will be made persistent.
	 * @param project the project to update
	 */
	public void updateProject(final Project project) {
		if (project == null) {
			return;
		}

		// TODO: Check if exists
		try {
			final PreparedStatement preparedStatement = connection.prepareStatement("update project set title = ?, description = ?, active = ? where id = ?"); //$NON-NLS-1$
			preparedStatement.setString(1, project.getTitle());
			preparedStatement.setString(2, project.getDescription());
			preparedStatement.setBoolean(3, project.isActive());
			preparedStatement.setLong(4, project.getId());

			preparedStatement.execute();
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

	/**
	 * Updates the activity in the database. Pending changes will be made persistent.
	 * @param activity the activity to update
	 */
	public void updateActivity(final ProjectActivity activity) {
		if (activity == null) {
			return;
		}

		// TODO: Check if exists
		try {
			final PreparedStatement preparedStatement = connection.prepareStatement("update activity set description = ?, start = ?, end = ?, project_id = ? where id = ?"); //$NON-NLS-1$

			preparedStatement.setString(1, activity.getDescription());

			final Timestamp d = new Timestamp( activity.getStart().getMillis());
			preparedStatement.setTimestamp(2,d);

			final Timestamp endDate = new Timestamp( activity.getEnd().getMillis());
			preparedStatement.setTimestamp(3, endDate);

			preparedStatement.setLong(4, activity.getProject().getId());
			preparedStatement.setLong(5, activity.getId());

			preparedStatement.execute();

		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

	/**
	 * Find a project by it's id.
	 * @param projectId the id of the project
	 * @return the project with the given id or <code>null</code> if there is none
	 */
	public Project findProjectById(final Long projectId) {
		if (projectId == null) {
			return null;
		}

		try {
			final PreparedStatement preparedStatement = connection.prepareStatement("select * from project where id = ?"); //$NON-NLS-1$
			preparedStatement.setLong(1, projectId);

			ResultSet rs = preparedStatement.executeQuery();
			if (rs.next()) {
				final Project project = new Project(rs.getLong("id"), rs.getString("title"), rs.getString("description")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				project.setActive(rs.getBoolean("active")); //$NON-NLS-1$
				return project;
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}

		return null;
	}
	
	/**
	 * Provides a list of all months with activities.
	 */
	public List<Integer> getMonthList() {
		final List<Integer> monthList = new ArrayList<Integer>();
		
		try {
			final Statement statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery("select distinct month(activity.start) as month from activity order by month desc"); //$NON-NLS-1$
			while (resultSet.next()) {
				monthList.add(resultSet.getInt("month")); //$NON-NLS-1$
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
		
		return monthList;
	}
	
	/**
	 * Provides a list of all years with activities.
	 */
	public List<Integer> getYearList() {
		final List<Integer> yearList = new ArrayList<Integer>();
		
		try {
			final Statement statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery("select distinct year(activity.start) as year from activity order by year desc"); //$NON-NLS-1$
			while (resultSet.next()) {
				yearList.add(resultSet.getInt("year")); //$NON-NLS-1$
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
		
		return yearList;
	}
	
	/**
	 * Provides a list of all weeks of year with activities.
	 */
	public List<Integer> getWeekOfYearList() {
		final List<Integer> weekOfYearList = new ArrayList<Integer>();
		
		try {
			final Statement statement = connection.createStatement();
			final ResultSet resultSet = statement.executeQuery("select distinct week(activity.start) as week from activity order by week desc"); //$NON-NLS-1$
			while (resultSet.next()) {
				weekOfYearList.add(resultSet.getInt("week")); //$NON-NLS-1$
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
		
		return weekOfYearList;
	}

	/**
	 * Provides all activities satisfying the given filter.
	 * @param filter the filter for activities
	 * @return read-only view of the activities
	 */
	public List<ProjectActivity> getActivities(final Filter filter) {
		if (filter == null) {
			return getActivities();
		}
		
		final StringBuilder sqlCondition = new StringBuilder(""); //$NON-NLS-1$
		
		// Condition for project
		if (filter.getProject() != null && filter.getProject().getId() > 0) {
			sqlCondition.append(" and activity.project_id = '" + filter.getProject().getId() + "' "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		// Condition for day of week
		if (filter.getDay() != null) {
			// :TRICKY: Day of the week in joda time and h2 database differ by one. Therfore
			// we have to add one and make sure that it never succeeds 7.
			final int dayOfWeek = (filter.getDay() + 1) % 7;
			sqlCondition.append(" and day_of_week(activity.start) = " + dayOfWeek + " "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		// Condition for week of year
		if (filter.getWeekOfYear() != null) {
			sqlCondition.append(" and week(activity.start) = " + filter.getWeekOfYear() + " "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		// Condition for month
		if (filter.getMonth() != null) {
			sqlCondition.append(" and month(activity.start) = " + filter.getMonth() + " "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		// Condition for year
		if (filter.getYear() != null) {
			sqlCondition.append(" and year(activity.start) = " + filter.getYear() + " "); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		return getActivities(sqlCondition.toString());
	}
	
	/**
	 * Gathers some statistics about the tracked activities.
	 */
	public void gatherStatistics() {
		try {
			final Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery("select count(*) as rowcount from activity"); //$NON-NLS-1$
			if (resultSet.next()) {
				log.error("#activities: " + resultSet.getInt("rowcount")); //$NON-NLS-1$
			}
			
			resultSet = statement.executeQuery("select count(*) as rowcount from project"); //$NON-NLS-1$
			if (resultSet.next()) {
				log.error("#projects: " + resultSet.getInt("rowcount")); //$NON-NLS-1$
			}

			Date earliestDate = null;
			resultSet = statement.executeQuery("select min(start) as startDate from activity"); //$NON-NLS-1$
			if (resultSet.next()) {
				earliestDate = resultSet.getDate("startDate");
				log.error("earliest activity: " + earliestDate); //$NON-NLS-1$
			}
			
			Date latestDate = null;
			resultSet = statement.executeQuery("select max(start) as startDate from activity"); //$NON-NLS-1$
			if (resultSet.next()) {
				latestDate = resultSet.getDate("startDate");
				log.error("latest activity: " + latestDate); //$NON-NLS-1$
			}
			
			if (earliestDate != null && latestDate != null) {
				Duration dur = new Duration(earliestDate.getTime(), latestDate.getTime());
				log.error("using for " + PeriodFormat.getDefault().print(dur.toPeriod())); //$NON-NLS-1$
				
			}
		} catch (SQLException e) {
			log.error(e, e);
			throw new RuntimeException(textBundle.textFor("BaralgaDB.DatabaseError.Message"), e); //$NON-NLS-1$
		}
	}

}
