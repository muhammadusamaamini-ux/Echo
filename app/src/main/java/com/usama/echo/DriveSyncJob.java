package com.usama.echo;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Keeps imported Google Drive folders in sync, about every hour, even when Echo is closed. */
public class DriveSyncJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Drive.syncAll(this, r -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
