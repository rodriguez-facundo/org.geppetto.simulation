
package org.geppetto.simulation.manager;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geppetto.core.beans.PathConfiguration;
import org.geppetto.core.common.GeppettoAccessException;
import org.geppetto.core.common.GeppettoExecutionException;
import org.geppetto.core.common.GeppettoInitializationException;
import org.geppetto.core.data.DataManagerHelper;
import org.geppetto.core.data.model.ExperimentStatus;
import org.geppetto.core.data.model.IExperiment;
import org.geppetto.core.data.model.IGeppettoProject;
import org.geppetto.core.data.model.IPersistedData;
import org.geppetto.core.data.model.ISimulationResult;
import org.geppetto.core.data.model.IUser;
import org.geppetto.core.data.model.IView;
import org.geppetto.core.data.model.ResultsFormat;
import org.geppetto.core.data.model.UserPrivileges;
import org.geppetto.core.datasources.GeppettoDataSourceException;
import org.geppetto.core.manager.IGeppettoManager;
import org.geppetto.core.manager.Scope;
import org.geppetto.core.model.GeppettoModelReader;
import org.geppetto.core.s3.S3Manager;
import org.geppetto.core.services.DropboxUploadService;
import org.geppetto.core.simulation.IGeppettoManagerCallbackListener;
import org.geppetto.core.utilities.URLReader;
import org.geppetto.core.utilities.Zipper;
import org.geppetto.model.ExperimentState;
import org.geppetto.model.GeppettoModel;
import org.geppetto.model.ModelFormat;
import org.geppetto.model.datasources.QueryResults;
import org.geppetto.model.datasources.RunnableQuery;
import org.geppetto.model.util.GeppettoModelException;
import org.geppetto.model.util.GeppettoModelTraversal;
import org.geppetto.model.util.GeppettoVisitingException;
import org.geppetto.simulation.utilities.GeppettoProjectZipper;
import org.geppetto.simulation.visitor.GeppettoModelTypesVisitor;
import org.geppetto.simulation.visitor.PersistModelVisitor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

/**
 * GeppettoManager is the implementation of IGeppettoManager which represents the Java API entry point for Geppetto. This class is instantiated with a session scope, which means there is one
 * GeppettoManager per each session/connection therefore only one user is associated with a GeppettoManager. A GeppettoManager is also instantiated by the ExperimentRunManager to handle the queued
 * activities in the database.
 * 
 * @author dandromereschi
 * @author matteocantarelli
 * 
 */
@Component
public class GeppettoManager implements IGeppettoManager
{

	private static Log logger = LogFactory.getLog(GeppettoManager.class);

	// these are the runtime projects for a
	private Map<IGeppettoProject, RuntimeProject> projects = new LinkedHashMap<>();

	private DropboxUploadService dropboxService = new DropboxUploadService();

	private IUser user;

	// By default
	private Scope scope = Scope.CONNECTION;

	private IGeppettoManagerCallbackListener geppettoManagerCallbackListener;

	public GeppettoManager()
	{
		SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);
		logger.info("New Geppetto Manager class");
	}

	public GeppettoManager(IGeppettoManager manager)
	{
		super();
		if(manager instanceof GeppettoManager)
		{
			GeppettoManager other = (GeppettoManager) manager;
			if(other.projects!=null) {
				this.projects.putAll(other.projects);
			}
			this.user = DataManagerHelper.getDataManager().getUserByLogin(other.getUser().getLogin());
		}
	}

	public GeppettoManager(Scope scope)
	{
		super();
		this.scope = scope;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#loadProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	public void loadProject(String requestId, IGeppettoProject project) throws MalformedURLException, GeppettoInitializationException, GeppettoExecutionException, GeppettoAccessException
	{
		if(!getScope().equals(Scope.RUN))
		{
			if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.READ_PROJECT))
			{
				throw new GeppettoAccessException("Insufficient access rights to load project.");
			}
			if(!project.isVolatile())
			{
				// if the project is persisted...
				if(!project.isPublic())
				{
					// and it's not public...
					if(!isUserProject(project.getId()))
					{
						// and doesn't belong to the current user
						throw new GeppettoAccessException("Project not found for the current user.");
					}
				}
			}
		}
		if(!projects.containsKey(project))
		{
			RuntimeProject runtimeProject = new RuntimeProject(project, this);
			projects.put(project, runtimeProject);
		}
		else
		{
			throw new GeppettoExecutionException("Cannot load two instances of the same project");
		}

	}

	public boolean isUserProject(long id)
	{
		if(DataManagerHelper.getDataManager().isDefault())
		{
			return true;
		}
		else
		{
			if(user != null)
			{
				List<? extends IGeppettoProject> userProjects = user.getGeppettoProjects();
				for(IGeppettoProject p : userProjects)
				{
					if(p != null)
					{
						if(p.getId() == id)
						{
							return true;
						}
					}
				}
			}

		}

		return false;
	}

	public boolean isProjectOpen(IGeppettoProject project)
	{
		if(projects.containsKey(project))
		{
			return true;
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#closeProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	public void closeProject(String requestId, IGeppettoProject project) throws GeppettoExecutionException
	{
		if(projects.size() > 0)
		{
			if(!projects.containsKey(project) && projects.get(project) == null)
			{
				throw new GeppettoExecutionException("A project without a runtime project cannot be closed");
			}
			try
			{
				PathConfiguration.deleteProjectTmpFolder(getScope(), project.getId());
			}
			catch(IOException e)
			{
				throw new GeppettoExecutionException(e);
			}
			projects.get(project).release();
			projects.remove(project);
		}
	}

	/**
	 * @param project
	 * @return
	 * @throws GeppettoExecutionException
	 */
	public RuntimeProject getRuntimeProject(IGeppettoProject project) throws GeppettoExecutionException
	{
		if(!projects.containsKey(project))
		{
			try
			{
				loadProject(null, project);
			}
			catch(MalformedURLException e)
			{
				throw new GeppettoExecutionException(e);
			}
			catch(GeppettoInitializationException e)
			{
				throw new GeppettoExecutionException(e);
			}
			catch(GeppettoAccessException e)
			{
				throw new GeppettoExecutionException(e);
			}
		}
		return projects.get(project);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#loadExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public ExperimentState loadExperiment(String requestId, IExperiment experiment) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!getScope().equals(Scope.RUN) && !user.getUserGroup().getPrivileges().contains(UserPrivileges.READ_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to load experiment.");
		}

		IGeppettoProject project = experiment.getParentProject();
		try
		{
			if(!projects.containsKey(project) || projects.get(project) == null)
			{
				throw new GeppettoExecutionException("Cannot load an experiment for a project that was not loaded");
			}
			getRuntimeProject(project).openExperiment(requestId, experiment);
		}
		catch(MalformedURLException | GeppettoInitializationException e)
		{
			throw new GeppettoExecutionException(e);
		}

		getRuntimeProject(project).setActiveExperiment(experiment);
		return getRuntimeProject(project).getRuntimeExperiment(experiment).getExperimentState();

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#runExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void runExperiment(String requestId, IExperiment experiment) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!getScope().equals(Scope.RUN) && !user.getUserGroup().getPrivileges().contains(UserPrivileges.RUN_EXPERIMENT))
		{
			throw new GeppettoAccessException("Insufficient access rights to run experiment.");
		}

		if(experiment.getStatus().equals(ExperimentStatus.DESIGN))
		{
			ExperimentRunManager.getInstance().queueExperiment(user, experiment);
		}
		else
		{
			throw new GeppettoExecutionException("Cannot run an experiment whose status is not design");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#playExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment)
	 */
	@Override
	public ExperimentState getExperimentState(String requestId, IExperiment experiment, List<String> variables) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.READ_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to play experiment.");
		}

		if(experiment.getStatus().equals(ExperimentStatus.COMPLETED))
		{
			String urlBase = experiment.getParentProject().getBaseURL();
			return getRuntimeProject(experiment.getParentProject()).getRuntimeExperiment(experiment).getExperimentState(variables, urlBase);

		}
		else
		{
			throw new GeppettoExecutionException("Cannot play an experiment whose status is not completed");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#deleteProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void makeProjectPublic(String requestId, IGeppettoProject project, boolean isPublic) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to make project public.");
		}

		DataManagerHelper.getDataManager().makeGeppettoProjectPublic(project.getId(), isPublic);
		;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#deleteProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void deleteProject(String requestId, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to delete project.");
		}

		DataManagerHelper.getDataManager().deleteGeppettoProject(project.getId(), user);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#persistProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void persistProject(String requestId, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to persist project.");
		}

		try
		{
			if(project.isVolatile())
			{
				if(getRuntimeProject(project).getActiveExperiment() != null)
				{
					// the project will have a new id after saving it therefore we update the hashmap as the hashcode will be different
					// since it's id based
					DataManagerHelper.getDataManager().addGeppettoProject(project, getUser());

                                        // update the ids of ExperimentState objects
                                        RuntimeProject runtimeProject = getRuntimeProject(project);
                                        for (IExperiment experiment : project.getExperiments()) {
                                            ExperimentState experimentState = runtimeProject.getRuntimeExperiment(experiment).getExperimentState();
                                            experimentState.setExperimentId(experiment.getId());
                                            experimentState.setProjectId(project.getId());
                                        }

					URL url = URLReader.getURL(project.getGeppettoModel().getUrl(), project.getBaseURL());
					Path localGeppettoModelFile = Paths.get(URLReader.createLocalCopy(scope, project.getId(), url, true).toURI());

					// save each model inside GeppettoModel and save every file referenced inside every model
					PersistModelVisitor persistModelVisitor = new PersistModelVisitor(localGeppettoModelFile, getRuntimeProject(project), project);
					try
					{
						GeppettoModelTraversal.apply(getRuntimeProject(project).getGeppettoModel(), persistModelVisitor);
					}
					catch(GeppettoVisitingException e)
					{
						throw new GeppettoExecutionException(e);
					}
					persistModelVisitor.processLocalGeppettoFile();
					String fileName = URLReader.getFileName(url);
					String newPath = "projects/" + Long.toString(project.getId()) + "/" + fileName;
					S3Manager.getInstance().saveFileToS3(localGeppettoModelFile.toFile(), newPath);
					project.getGeppettoModel().setURL(S3Manager.getInstance().getURL(newPath).toString());
					// save Geppetto Scripts
					for(IExperiment experiment : project.getExperiments())
					{
						if(experiment.getScript() != null)
						{
							URL scriptURL = URLReader.getURL(experiment.getScript(), project.getBaseURL());
							Path localScript = Paths.get(URLReader.createLocalCopy(scope, project.getId(), scriptURL, true).toURI());
							String newScriptPath = "projects/" + Long.toString(project.getId()) + "/experiment/" + experiment.getId() + "/script.js";
							S3Manager.getInstance().saveFileToS3(localScript.toFile(), newScriptPath);
							experiment.setScript(S3Manager.getInstance().getURL(newScriptPath).toString());
						}

						if(experiment.getSimulationResults() != null)
						{
							for(ISimulationResult simResult : experiment.getSimulationResults())
							{
								if(simResult.getResult() != null)
								{
									URL resultURL = URLReader.getURL(simResult.getResult().getUrl(), project.getBaseURL());
									String resultFileName = URLReader.getFileName(resultURL);
									Path localResult = Paths.get(URLReader.createLocalCopy(scope, project.getId(), resultURL, true).toURI());
									String newResultPath = "projects/" + Long.toString(project.getId()) + "/experiment/" + experiment.getId() + "/" + resultFileName;
									S3Manager.getInstance().saveFileToS3(localResult.toFile(), newResultPath);
									simResult.getResult().setURL(S3Manager.getInstance().getURL(newResultPath).toString());
								}
							}
						}
					}
					// we call setVolatile only at the very end, there might be other things like setView trying to save the project
					project.setVolatile(false);
					DataManagerHelper.getDataManager().saveEntity(project);

				}
				else
				{
					throw new GeppettoExecutionException("Cannot persist a project without an active experiment");
				}
			}
			else
			{
				throw new GeppettoExecutionException("Persist failed: Project '" + project.getName() + "' is already persisted");
			}
		}
		catch(IOException | URISyntaxException e)
		{
			throw new GeppettoExecutionException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#newExperiment(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public IExperiment newExperiment(String requestId, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to create new experiment.");
		}

		IExperiment experiment = DataManagerHelper.getDataManager().newExperiment("New Experiment " + (project.getExperiments().size() + 1), "", project);
		try
		{
			getRuntimeProject(project).populateNewExperiment(experiment);
		}
		catch(GeppettoVisitingException e)
		{
			throw new GeppettoExecutionException(e);
		}
		return experiment;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#newExperiment(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public IExperiment cloneExperiment(String requestId, IGeppettoProject project, IExperiment originalExperiment) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to create new experiment.");
		}

		IExperiment experiment = DataManagerHelper.getDataManager().cloneExperiment("New Experiment " + (project.getExperiments().size() + 1), "", project, originalExperiment);
		return experiment;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#deleteExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void deleteExperiment(String requestId, IExperiment experiment) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to delete experiment.");
		}

		IGeppettoProject project = experiment.getParentProject();
		if(getRuntimeProject(project).getRuntimeExperiment(experiment) != null)
		{
			getRuntimeProject(project).closeExperiment(experiment);
		}

		DataManagerHelper.getDataManager().deleteExperiment(experiment);
		if(project.getActiveExperimentId() == experiment.getId())
		{
			project.setActiveExperimentId(-1);
		}
		project.getExperiments().remove(experiment);
		DataManagerHelper.getDataManager().saveEntity(project);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#linkDropBoxAccount()
	 */
	@Override
	public void linkDropBoxAccount(String key) throws Exception
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.DROPBOX_INTEGRATION))
		{
			throw new GeppettoAccessException("Insufficient access rights to link dropbox account.");
		}

		String authToken = dropboxService.link(key);
		getUser().setDropboxToken(authToken);
		DataManagerHelper.getDataManager().saveEntity(getUser());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#unlinkDropBoxAccount()
	 */
	@Override
	public void unlinkDropBoxAccount(String key) throws Exception
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.DROPBOX_INTEGRATION))
		{
			throw new GeppettoAccessException("Insufficient access rights to unlink dropbox account.");
		}

		dropboxService.unlink(key);
	}

    public String getDropboxToken() throws Exception {
        if (getUser().getDropboxToken() != null)
            return user.getDropboxToken();
        else
            return null;
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#uploadModelToDropBox(java.lang.String, org.geppetto.core.services.IModelFormat)
	 */
	@Override
	public void uploadModelToDropBox(String aspectID, IExperiment experiment, IGeppettoProject project, ModelFormat format) throws Exception
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.DROPBOX_INTEGRATION))
		{
			throw new GeppettoAccessException("Insufficient access rights to upload model to dropbox.");
		}

		if(getUser() != null)
		{
			if(getUser().getDropboxToken() != null)
			{
				dropboxService.init(user.getDropboxToken());
			}
			// ConSvert model
			File file = this.downloadModel(aspectID, format, experiment, project);
			Zipper zipper = new Zipper(PathConfiguration.createExperimentTmpPath(Scope.CONNECTION, project.getId(), experiment.getId(), aspectID, file.getName() + ".zip"));
			Path path = zipper.getZipFromDirectory(file);
			dropboxService.upload(path.toFile());
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#uploadResultsToDropBox(java.lang.String, org.geppetto.core.simulation.ResultsFormat)
	 */
	@Override
	public void uploadResultsToDropBox(String aspectID, IExperiment experiment, IGeppettoProject project, ResultsFormat format) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.DROPBOX_INTEGRATION))
		{
			throw new GeppettoAccessException("Insufficient access rights to upload results to dropbox.");
		}

		if(getUser() != null)
		{
			if(getUser().getDropboxToken() != null)
			{
				dropboxService.init(user.getDropboxToken());
			}
		}
		getRuntimeProject(project).getRuntimeExperiment(experiment).uploadResults(aspectID, format, dropboxService);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#setModelParameters(java.lang.String, java.util.Map, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public ExperimentState setModelParameters(Map<String, String> parameters, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to set model parameters.");
		}

		ExperimentState setParameters = getRuntimeProject(project).getRuntimeExperiment(experiment).setModelParameters(parameters);
		DataManagerHelper.getDataManager().saveEntity(project);
		return setParameters;
	}

	/*
	 * (non-Javadoc)
	 */
	@Override
	public void setExperimentView(String view, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		IView v = null;

		if(experiment != null)
		{
			// save view at the experiment level
			if(experiment.getView() == null)
			{
				v = DataManagerHelper.getDataManager().newView(view, experiment);
			}
			else
			{
				v = experiment.getView();
				v.setView(view);
			}
		}
		else
		{
			// save view at the project level if experiment was not found
			// save view at the experiment level
			if(project.getView() == null)
			{
				v = DataManagerHelper.getDataManager().newView(view, project);
			}
			else
			{
				v = project.getView();
				v.setView(view);
			}
		}
		if(!project.isVolatile())
		{
			DataManagerHelper.getDataManager().saveEntity(v);
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#setWatchedVariables(java.util.List, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public ExperimentState setWatchedVariables(List<String> watchedVariables, IExperiment experiment, IGeppettoProject project, boolean watch)
			throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.WRITE_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to set watched variables.");
		}

		ExperimentState experimentState = getRuntimeProject(project).getRuntimeExperiment(experiment).setWatchedVariables(watchedVariables, watch);
		DataManagerHelper.getDataManager().saveEntity(project);
		return experimentState;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDownloadManager#downloadModel(java.lang.String, org.geppetto.core.services.IModelFormat)
	 */
	@Override
	public File downloadModel(String instancePath, ModelFormat format, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.DOWNLOAD))
		{
			throw new GeppettoAccessException("Insufficient access rights to download model.");
		}

		return getRuntimeProject(project).getRuntimeExperiment(experiment).downloadModel(instancePath, format);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDownloadManager#supportedOuputs(java.lang.String)
	 */
	@Override
	public List<ModelFormat> getSupportedOuputs(String aspectInstancePath, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.READ_PROJECT))
		{
			throw new GeppettoAccessException("Insufficient access rights to get supported outputs.");
		}

		return getRuntimeProject(project).getRuntimeExperiment(experiment).supportedOuputs(aspectInstancePath);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDownloadManager#downloadResults(org.geppetto.core.simulation.ResultsFormat)
	 */
	@Override
	public URL downloadResults(String aspectPath, ResultsFormat resultsFormat, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		if(!user.getUserGroup().getPrivileges().contains(UserPrivileges.DOWNLOAD))
		{
			throw new GeppettoAccessException("Insufficient access rights to download results.");
		}

		logger.info("Downloading results for " + aspectPath + " in format " + resultsFormat.toString());
		for(ISimulationResult result : experiment.getSimulationResults())
		{
			if(result.getSimulatedInstance().equals(aspectPath))
			{
				if(result.getFormat().equals(resultsFormat))
				{
					try
					{
						IPersistedData resultObject = result.getResult();
						return URLReader.getURL(resultObject.getUrl(), project.getBaseURL());
					}
					catch(Exception e)
					{
						throw new GeppettoExecutionException(e);
					}
				}
			}
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IGeppettoManager#getUser()
	 */
	public IUser getUser()
	{
		return user;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IGeppettoManager#setUser(org.geppetto.core.data.model.IUser)
	 */
	@Override
	public void setUser(IUser user) throws GeppettoExecutionException
	{
		if(this.user != null)
		{
			String message = "A GeppettoManager is being reused, an user was already set and setUser is being called. Current user:" + this.user.getName() + ", attempted new user:" + user.getName();
			logger.error(message);
			throw new GeppettoExecutionException(message);
		}
		this.user = user;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#cancelExperimentRun(java.lang.String, org.geppetto.core.data.model.IExperiment)
	 */
	@Override
	public void cancelExperimentRun(String requestId, IExperiment experiment) throws GeppettoExecutionException
	{
		ExperimentRunManager.getInstance().cancelExperimentRun(getUser(), experiment);
		experiment.setStatus(ExperimentStatus.DESIGN);
		DataManagerHelper.getDataManager().saveEntity(experiment);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#checkExperimentsStatus(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public List<? extends IExperiment> checkExperimentsStatus(String requestId, IGeppettoProject project)
	{
		// TODO This could be more sophisticated and return only the projects which have changed their status because of a run
		return project.getExperiments();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IGeppettoManager#getScope()
	 */
	@Override
	public Scope getScope()
	{
		return scope;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDataSourceManager#fetchVariable(java.lang.String, java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public GeppettoModel fetchVariable(String dataSourceId, String[] variableId, IGeppettoProject project) throws GeppettoDataSourceException, GeppettoModelException, GeppettoExecutionException
	{
		return getRuntimeProject(project).fetchVariable(dataSourceId, variableId);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#resolveImportType(java.util.List, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public GeppettoModel resolveImportType(List<String> typePaths, IGeppettoProject geppettoProject) throws GeppettoExecutionException
	{
		return getRuntimeProject(geppettoProject).resolveImportType(typePaths);
	}

	@Override
	public void setSimulationListener(IGeppettoManagerCallbackListener listener)
	{
		this.geppettoManagerCallbackListener = listener;
		ExperimentRunManager.getInstance().setExperimentListener(this.geppettoManagerCallbackListener);
	}

	@Override
	public GeppettoModel resolveImportValue(String path, IExperiment experiment, IGeppettoProject geppettoProject)
	{
		try
		{
			return getRuntimeProject(geppettoProject).resolveImportValue(path);
		}
		catch(GeppettoExecutionException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDataSourceManager#runQuery(java.util.List, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public QueryResults runQuery(List<RunnableQuery> queries, IGeppettoProject project) throws GeppettoModelException, GeppettoExecutionException, GeppettoDataSourceException
	{
		return getRuntimeProject(project).runQuery(queries);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDataSourceManager#runQueryCount(java.util.List, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public int runQueryCount(List<RunnableQuery> queries, IGeppettoProject project) throws GeppettoExecutionException, GeppettoModelException, GeppettoDataSourceException
	{
		return getRuntimeProject(project).runQueryCount(queries);
	}

	@Override
	public Path downloadProject(IGeppettoProject project) throws GeppettoExecutionException, GeppettoAccessException
	{
		Path zip = null;
		try
		{
			File dir = new File(PathConfiguration.getProjectTmpPath(scope, project.getId()));
			dir.mkdirs();
			Zipper zipper = new Zipper(dir.getAbsolutePath() + "/project.zip", "Project_" + project.getId());

			String modelPath = project.getGeppettoModel().getUrl();

			GeppettoProjectZipper geppettoProjectZipper = new GeppettoProjectZipper();
			File jsonFile = geppettoProjectZipper.writeIGeppettoProjectToJson(project, dir, zipper, project.getBaseURL());
			zipper.addToZip(jsonFile.toURI().toURL());

			URL url = URLReader.getURL(modelPath, project.getBaseURL());

			GeppettoModel geppettoModel = GeppettoModelReader.readGeppettoModel(url);
			Path localGeppettoModelFile = Paths.get(URLReader.createLocalCopy(scope, project.getId(), url, false).toURI());

			// Changes paths inside the .XMI
			GeppettoModelTypesVisitor importTypesVisitor = new GeppettoModelTypesVisitor(localGeppettoModelFile, getRuntimeProject(project), zipper, this.getScope());
			GeppettoModelTraversal.apply(geppettoModel, importTypesVisitor);
			zipper.addToZip(localGeppettoModelFile.toUri().toURL());

			zip = zipper.processAddedFilesAndZip();
		}
		catch(Exception e)
		{
			logger.error("Unable to download project" + e);
			throw new GeppettoExecutionException(e);
		}

		return zip;
	}
}
