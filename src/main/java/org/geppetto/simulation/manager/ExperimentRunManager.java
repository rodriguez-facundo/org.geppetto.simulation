
package org.geppetto.simulation.manager;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geppetto.core.common.GeppettoExecutionException;
import org.geppetto.core.common.GeppettoInitializationException;
import org.geppetto.core.data.DataManagerHelper;
import org.geppetto.core.data.IGeppettoDataManager;
import org.geppetto.core.data.model.ExperimentStatus;
import org.geppetto.core.data.model.IExperiment;
import org.geppetto.core.data.model.IGeppettoProject;
import org.geppetto.core.data.model.IUser;
import org.geppetto.core.manager.Scope;
import org.geppetto.core.simulation.IGeppettoManagerCallbackListener;
import org.geppetto.simulation.IExperimentListener;

/**
 * The ExperimentRunManager is a singleton responsible for managing a queue per each user to run the experiments.
 * 
 * @author dandromereschi
 * @author matteocantarelli
 *
 */
public class ExperimentRunManager implements IExperimentListener
{

	private Map<IUser, BlockingQueue<IExperiment>> queue;

	private GeppettoManager geppettoManager;

	private volatile int reqId = 0;

	private Timer timer;

	private IGeppettoManagerCallbackListener geppettoManagerCallbackListener;

	private Map<IGeppettoProject, Integer> experimentsCounter = new ConcurrentHashMap<>();

	private Set<IGeppettoProject> projectsRunning = new HashSet<IGeppettoProject>();

	private static ExperimentRunManager instance = null;

	/**
	 * @return
	 */
	public static ExperimentRunManager getInstance()
	{
		if(instance == null)
		{
			instance = new ExperimentRunManager();
		}
		return instance;
	}

	/**
	 * 
	 */
	private ExperimentRunManager()
	{
		if(instance == null)
		{
			instance = this;
			queue = new ConcurrentHashMap<>();
			geppettoManager = new GeppettoManager(Scope.RUN);
			try
			{
				loadExperiments();
				timer = new Timer("ExperimentRunChecker");
				timer.schedule(new ExperimentRunChecker(), 0, 1000);
			}
			catch(GeppettoInitializationException | GeppettoExecutionException | MalformedURLException e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * @param user
	 * @param experiment
	 */
	public synchronized void queueExperiment(IUser user, IExperiment experiment)
	{
		experiment.setStatus(ExperimentStatus.QUEUED);

		addExperimentToQueue(user, experiment, ExperimentStatus.QUEUED);
	}

	/**
	 * @param experiment
	 * @return
	 * @throws GeppettoInitializationException
	 */
	public boolean checkExperiment(IExperiment experiment) throws GeppettoInitializationException
	{
		boolean experimentCanRun = experiment.getStatus().equals(ExperimentStatus.QUEUED);
		if(experimentCanRun)
		{
			IGeppettoProject project = experiment.getParentProject();
			if(projectsRunning.contains(project))
			{
				return false;
			}
		}
		return experimentCanRun;
	}

	/**
	 * @param experiment
	 */
	private void decreaseExperimentCounter(IExperiment experiment)
	{
		experimentsCounter.put(experiment.getParentProject(), experimentsCounter.get(experiment.getParentProject()) - 1);
	}

	/**
	 * @param experimentAboutToRun
	 */
	private void increaseExperimentCounter(IExperiment experimentAboutToRun)
	{
		IGeppettoProject project = experimentAboutToRun.getParentProject();
		if(!experimentsCounter.containsKey(project))
		{
			experimentsCounter.put(project, 0);
		}
		experimentsCounter.put(project, experimentsCounter.get(project) + 1);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.simulation.IExperimentRunManager#runExperiment(org.geppetto.core.data.model.IExperiment)
	 */
	void runExperiment(IExperiment experiment) throws GeppettoExecutionException
	{
		try
		{
			IGeppettoProject project = experiment.getParentProject();
			projectsRunning.add(project);
			if(!geppettoManager.isProjectOpen(project))
			{
				geppettoManager.loadProject(String.valueOf(this.getReqId()), project);
			}
			RuntimeProject runtimeProject = geppettoManager.getRuntimeProject(project);
			runtimeProject.openExperiment(String.valueOf(this.getReqId()), experiment);

			ExperimentRunThread experimentRun = new ExperimentRunThread(experiment, runtimeProject, this);
			experimentRun.start();
			experiment.setStatus(ExperimentStatus.RUNNING);
			experiment.updateStartDate();
			DataManagerHelper.getDataManager().saveEntity(experiment);

		}
		catch(Exception e)
		{
			simulationError(experiment);
			experiment.updateEndDate();
			String errorMessage = "Error running experiment with name: " + experiment.getName() + " and id: " + experiment.getId();
			this.experimentError(errorMessage, e.getMessage(), e, experiment);
			throw new GeppettoExecutionException(e);
		}
	}

	/**
	 * 
	 */
	private void simulationError(IExperiment experiment)
	{
		experiment.setStatus(ExperimentStatus.ERROR);
		DataManagerHelper.getDataManager().saveEntity(experiment);
	}

	/**
	 * @throws GeppettoInitializationException
	 * @throws MalformedURLException
	 * @throws GeppettoExecutionException
	 */
	private void loadExperiments() throws GeppettoInitializationException, MalformedURLException, GeppettoExecutionException
	{
		IGeppettoDataManager dataManager = DataManagerHelper.getDataManager();

		List<? extends IUser> users = dataManager.getAllUsers();
		for(IUser user : users)
		{
			for(IGeppettoProject project : user.getGeppettoProjects())
			{
				for(IExperiment e : project.getExperiments())
				{
					e.setParentProject(project);
					if(e.getStatus().equals(ExperimentStatus.RUNNING))
					{
						addExperimentToQueue(user, e, e.getStatus());
					}
				}
				for(IExperiment e : project.getExperiments())
				{
					if(e.getStatus().equals(ExperimentStatus.QUEUED))
					{
						addExperimentToQueue(user, e, e.getStatus());
					}
				}
			}
			dataManager.saveEntity(user);
		}
	}

	/**
	 * @param user
	 * @param experiment
	 * @param status
	 */
	private synchronized void addExperimentToQueue(IUser user, IExperiment experiment, ExperimentStatus status)
	{
		increaseExperimentCounter(experiment);
		BlockingQueue<IExperiment> userExperiments = queue.get(user);
		if(userExperiments == null)
		{
			userExperiments = new ArrayBlockingQueue<IExperiment>(100);
			queue.put(user, userExperiments);
		}
		if(experiment.getStatus() == status)
		{
			experiment.setStatus(ExperimentStatus.QUEUED);
			userExperiments.add(experiment);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.simulation.IExperimentListener#experimentRunDone(org.geppetto.simulation.ExperimentRun, org.geppetto.core.data.model.IExperiment)
	 */
	@Override
	public void experimentRunDone(ExperimentRunThread experimentRun, IExperiment experiment, RuntimeProject project) throws GeppettoExecutionException
	{
		// if we are using the default data manager it means there is no persistence bundle
		// if we are running an experiment in this scenarios it is because of a test rather
		// than any real deployment. In test scenarios some flows like upload results to
		// S3 are not performed (due to security reason tokens are not committed) and therefore
		// as a workaround we are not clearing after the temporary files in the tests so that
		// we can use them to check that the simulation was properly executed.
		// This is not ideal or particularly elegant but harmless at the same time until we
		// can think of a better way.
		experimentRun.release();
		decreaseExperimentCounter(experiment);
		projectsRunning.remove(experiment.getParentProject());
		if(!DataManagerHelper.getDataManager().isDefault() && experimentsCounter.get(experiment.getParentProject()) == 0)
		{
			experimentsCounter.remove(experiment.getParentProject());
			geppettoManager.closeProject("ERM" + getReqId(), project.getGeppettoProject());
		}
	}

	/**
	 * @return
	 */
	private synchronized int getReqId()
	{
		return ++reqId;
	}

	public Map<IUser, BlockingQueue<IExperiment>> getQueuedExperiments()
	{
		return this.queue;
	}

	/**
	 * @param user
	 * @param experiment
	 * @throws GeppettoExecutionException
	 */
	public void cancelExperimentRun(IUser user, IExperiment experiment) throws GeppettoExecutionException
	{
		BlockingQueue<IExperiment> queuedExperiments = getQueuedExperiments().get(user);
		if(queuedExperiments != null)
		{
			if(queuedExperiments.contains(experiment))
			{
				getQueuedExperiments().get(user).remove(experiment);
			}
			else
			{
				throw new GeppettoExecutionException("The experiment " + experiment.getId() + " is not queued.");
			}
		}
		else
		{
			throw new GeppettoExecutionException("The user " + user.getName() + " has no queued experiments.");
		}

	}

	@Override
	public void experimentError(String titleMessage, String errorMessage, Exception exception, IExperiment experiment)
	{
		this.geppettoManagerCallbackListener.experimentError(titleMessage, errorMessage, exception, experiment);
	}

	public void setExperimentListener(IGeppettoManagerCallbackListener listener)
	{
		this.geppettoManagerCallbackListener = listener;
	}

}

class ExperimentRunChecker extends TimerTask
{
	private static Log logger = LogFactory.getLog(ExperimentRunChecker.class);
	private Map<IUser, BlockingQueue<IExperiment>> queuedExperiments = ExperimentRunManager.getInstance().getQueuedExperiments();

	public synchronized void run()
	{
		try
		{
			for(IUser user : queuedExperiments.keySet())
			{
				List<IExperiment> ran = new ArrayList<IExperiment>();
				for(IExperiment e : queuedExperiments.get(user))
				{
					if(ExperimentRunManager.getInstance().checkExperiment(e))
					{
						logger.info("Experiment queued found " + e.getName());
						ExperimentRunManager.getInstance().runExperiment(e);
						ran.add(e);
					}
				}
				for(IExperiment ranExperiment : ran)
				{
					queuedExperiments.get(user).remove(ranExperiment);
				}
			}
		}
		catch(GeppettoExecutionException | GeppettoInitializationException e)
		{
			logger.error(e);
		}
	}
}
