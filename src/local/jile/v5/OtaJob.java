package local.jile.v5;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ConcurrentHashMap;

public final class OtaJob extends JobService {
    private final ConcurrentHashMap<JobParameters, Thread> tasks = new ConcurrentHashMap<>();

    @Override public boolean onStartJob(JobParameters params) {
        Thread task = new Thread(() -> {
            boolean complete = new OtaUpdate(this).check(params.getJobId() == OtaUpdate.PERIODIC);
            if (tasks.remove(params, Thread.currentThread())) jobFinished(params, !complete);
        }, "jile-update");
        tasks.put(params, task);
        task.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread task = tasks.remove(params);
        if (task != null) task.interrupt();
        return true;
    }
}
