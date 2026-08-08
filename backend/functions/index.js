"use strict";

const {onSchedule} = require("firebase-functions/v2/scheduler");
const {logger} = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

const RETENTION_DAYS = 7;
const TIME_ZONE = "Asia/Kolkata";
const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Return the oldest date key that should be retained.
 *
 * For a seven-day window this returns today minus six calendar days. Any
 * yyyy-MM-dd key before this cutoff is expired.
 */
function getRetentionCutoff(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);

  const values = {};
  for (const part of parts) {
    if (part.type !== "literal") {
      values[part.type] = Number(part.value);
    }
  }

  const cutoff = new Date(Date.UTC(
      values.year,
      values.month - 1,
      values.day - (RETENTION_DAYS - 1),
  ));

  return cutoff.toISOString().slice(0, 10);
}

function addExpiredDateDeletes(
    updates,
    devices,
    basePath,
    cutoffDateKey,
    dateContainer = "",
) {
  if (!devices || typeof devices !== "object") {
    return 0;
  }

  let deleteCount = 0;
  for (const [deviceId, dates] of Object.entries(devices)) {
    if (!dates || typeof dates !== "object") {
      continue;
    }

    for (const dateKey of Object.keys(dates)) {
      if (DATE_KEY_PATTERN.test(dateKey) && dateKey < cutoffDateKey) {
        updates[`${basePath}/${deviceId}${dateContainer}/${dateKey}`] = null;
        deleteCount++;
      }
    }
  }
  return deleteCount;
}

/**
 * Daily safety-net cleanup.
 *
 * Android uploads already replace each device's usage window with seven dates.
 * This privileged scheduled job also removes stale canonical and legacy dates
 * when a device has not uploaded a newer window.
 */
exports.cleanupUsageRetention = onSchedule(
    {
      schedule: "15 3 * * *",
      timeZone: TIME_ZONE,
      region: "asia-south1",
      timeoutSeconds: 540,
      memory: "256MiB",
    },
    async () => {
      const cutoffDateKey = getRetentionCutoff();
      const root = admin.database().ref();

      const [canonicalSnapshot, legacySnapshot] = await Promise.all([
        root.child("v2/usage_daily").once("value"),
        root.child("susage_data").once("value"),
      ]);

      const updates = {};
      let deleteCount = addExpiredDateDeletes(
          updates,
          canonicalSnapshot.val(),
          "v2/usage_daily",
          cutoffDateKey,
      );

      const legacyWeeklyData = {};
      legacySnapshot.forEach((deviceSnapshot) => {
        legacyWeeklyData[deviceSnapshot.key] =
          deviceSnapshot.child("weeklyData").val();
      });
      deleteCount += addExpiredDateDeletes(
          updates,
          legacyWeeklyData,
          "susage_data",
          cutoffDateKey,
          "/weeklyData",
      );

      if (deleteCount === 0) {
        logger.info("Usage retention cleanup found no expired dates", {
          cutoffDateKey,
        });
        return;
      }

      await root.update(updates);
      logger.info("Usage retention cleanup completed", {
        cutoffDateKey,
        deletedDates: deleteCount,
      });
    },
);
