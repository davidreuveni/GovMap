package il.david.govmap.dark;

import android.app.job.JobService;
import android.app.job.JobParameters;

public final class UpdateJobService extends JobService {
    private UpdateRelease.Transfer transfer;
    @Override public boolean onStartJob(JobParameters params) {
        transfer = new UpdateRelease.Transfer();
        final UpdateRelease.Transfer current = transfer;
        AppUpdates.get(this).check(false, current, () -> {
            if (transfer == current && !current.cancelled) { transfer = null; jobFinished(params, false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (transfer != null) transfer.cancel(); transfer = null; return true;
    }
}
