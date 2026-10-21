package id.hifaki.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncJob extends JobService {
    private static final int JOB_ID = 42107;
    private AtomicBoolean cancelled = new AtomicBoolean(false);

    public static void schedule(Context context) {
        Store store = new Store(context);
        int pending = store.pendingCount();
        store.close();
        if (pending == 0 || new SecurePrefs(context).url().isEmpty()) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params) {
        cancelled = new AtomicBoolean(false);
        new Thread(() -> {
            SyncEngine.Result result = SyncEngine.upload(getApplicationContext(), cancelled);
            if (!cancelled.get()) jobFinished(params, result.retry);
        }, "HifakiSync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelled.set(true);
        return true;
    }
}
