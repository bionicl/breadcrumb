#!/usr/bin/env python3
"""Add synthetic Review scenarios to an Android emulator, keeping a host-side DB backup.

Never targets a physical phone. The read-only debug bridge stays read-only; fixture writes
use run-as on a stopped emulator app. Re-run to replace only this script's synthetic rows.
"""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
from zoneinfo import ZoneInfo

PACKAGE = "io.github.valeronm.breadcrumb.debug"
SOURCE = "review_demo"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--day", default="2026-10-18")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    args = parser.parse_args()
    prefix = [args.adb, "-s", args.serial]

    def adb(*command, binary=False, data=None):
        return subprocess.run(prefix + list(command), input=data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, text=not binary).stdout

    if not args.serial.startswith("emulator-") or adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Refusing fixture writes: the target must be an Android emulator.")
    adb("shell", "am", "force-stop", PACKAGE)
    folder = Path(tempfile.mkdtemp(prefix="breadcrumb-review-demo-"))
    database = folder / "tracks.db"
    database.write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", "databases/tracks.db", binary=True))
    for suffix in ("-wal", "-shm"):
        try:
            (folder / ("tracks.db" + suffix)).write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", "databases/tracks.db" + suffix, binary=True))
        except subprocess.CalledProcessError:
            pass
    connection = sqlite3.connect(database)
    backup = sqlite3.connect(folder / "before-fixtures.db")
    connection.backup(backup)
    backup.close()
    connection.execute("PRAGMA foreign_keys=ON")

    def insert(table, **values):
        columns = connection.execute(f"PRAGMA table_info({table})").fetchall()
        for _, name, kind, required, default, primary in columns:
            if name not in values and required and default is None and not primary:
                values[name] = "" if "TEXT" in kind else 0
        names = list(values)
        return connection.execute(f"INSERT INTO {table} ({','.join(names)}) VALUES ({','.join('?' for _ in names)})",
                                  [values[name] for name in names]).lastrowid

    # Only our own rows; leave all existing emulator history intact.
    connection.execute("DELETE FROM review_queue WHERE id LIKE 'review-demo-%'")
    connection.execute("DELETE FROM review_queue WHERE primaryTrackId IN (SELECT id FROM tracks WHERE source=?) OR secondaryTrackId IN (SELECT id FROM tracks WHERE source=?)", (SOURCE, SOURCE))
    connection.execute("DELETE FROM route_analysis_jobs WHERE trackId IN (SELECT id FROM tracks WHERE source=?)", (SOURCE,))
    connection.execute("DELETE FROM engine_user_corrections WHERE targetSegmentId IN (SELECT 'derived-visit:' || id FROM tracks WHERE source=?)", (SOURCE,))
    connection.execute("DELETE FROM derived_intervals WHERE afterTrackId IN (SELECT id FROM tracks WHERE source=?)", (SOURCE,))
    connection.execute("DELETE FROM cluster_members WHERE trackId IN (SELECT id FROM tracks WHERE source=?)", (SOURCE,))
    connection.execute("DELETE FROM tracks WHERE source=?", (SOURCE,))
    connection.execute("DELETE FROM engine_observations WHERE sessionId='review-demo'")
    for place_id in (9200001, 9200002):
        connection.execute("DELETE FROM places WHERE id=? AND label LIKE '[Demo]%'", (place_id,))
    connection.execute("DELETE FROM derived_clusters WHERE id BETWEEN 9300000 AND 9300099")

    day = dt.date.fromisoformat(args.day)
    midnight = int(dt.datetime.combine(day, dt.time(), ZoneInfo("Europe/Warsaw")).timestamp() * 1000)
    now = midnight + 20 * 3600000

    def at(hour, minute=0):
        return midnight + (hour * 60 + minute) * 60000

    def place(place_id, cluster_id, label, lat, lon):
        insert("places", id=place_id, label=label, lat=lat, lon=lon, radiusM=75., createdAt=now, source="manual")
        insert("derived_clusters", id=cluster_id, placeId=place_id, anchorLat=lat, anchorLon=lon,
               radiusM=75., sumLat=0., sumLon=0., memberCount=0)

    place(9200001, 9300001, "[Demo] Koneser cafe", 52.25545, 21.04545)
    place(9200002, 9300002, "[Demo] Parking", 52.2578, 21.0425)

    def track(track_id, start, end, activity, waypoints, break_at=None):
        coords = []
        for a, b in zip(waypoints, waypoints[1:]):
            for step in range(16):
                fraction = step / 16
                coords.append((a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction))
        coords.append(waypoints[-1])
        distance = sum(math.hypot((b[0] - a[0]) * 111000, (b[1] - a[1]) * 68000) for a, b in zip(coords, coords[1:]))
        insert("tracks", id=track_id, activityType=activity, startedAt=start, endedAt=end, source=SOURCE,
               distanceMeters=distance, pointCount=len(coords), startLat=coords[0][0], startLon=coords[0][1],
               endLat=coords[-1][0], endLon=coords[-1][1], pathProcessed=1, pathRevision=1,
               pathProcessedRevision=1, pathProcessorVersion=1, pathParametersVersion=3)
        for index, (lat, lon) in enumerate(coords):
            insert("track_points", trackId=track_id, timestamp=start + (end-start)*index//(len(coords)-1),
                   latitude=lat, longitude=lon, accuracy=5., speed=distance/((end-start)/1000),
                   segmentStart=int(index == break_at))
        # Point-insert triggers advance the revision; mark exactly that final input as processed.
        revision = connection.execute("SELECT pathRevision FROM tracks WHERE id=?", (track_id,)).fetchone()[0]
        connection.execute("UPDATE tracks SET pathProcessedRevision=pathRevision WHERE id=?", (track_id,))
        insert("route_analysis_jobs", trackId=track_id, processorVersion=1, parametersVersion=3,
               inputRevision=revision, state="COMPLETE", attempts=1, updatedAtWallTimeMs=now)
        return track_id

    def stay(first, second, cluster_id, start, end, coordinate):
        lat, lon = coordinate
        insert("derived_clusters", id=cluster_id, anchorLat=lat, anchorLon=lon, radiusM=100.,
               sumLat=lat*2, sumLon=lon*2, memberCount=2)
        insert("cluster_members", trackId=first, isStart=0, atMs=start, clusterId=cluster_id, lat=lat, lon=lon)
        insert("cluster_members", trackId=second, isStart=1, atMs=end, clusterId=cluster_id, lat=lat, lon=lon)
        insert("derived_intervals", type="STAY", start=start, endedAt=end, afterTrackId=first, clusterId=cluster_id)

    def review(key, kind, first, second, start, end, reason, suggestion=None, current="UNASSIGNED"):
        revision = connection.execute("SELECT pathRevision FROM tracks WHERE id=?", (first,)).fetchone()[0]
        if second:
            revision = revision * 31 + connection.execute("SELECT pathRevision FROM tracks WHERE id=?", (second,)).fetchone()[0]
        insert("review_queue", id="review-demo-"+key, kind=kind, state="PENDING", primaryTrackId=first,
               secondaryTrackId=second, startWallTimeMs=start, endWallTimeMs=end, suggestedValue=suggestion,
               currentValue=current, reasonCode=reason, evidenceKey="review-demo-"+key, inputRevision=revision,
               createdAtWallTimeMs=now, updatedAtWallTimeMs=now)

    # A short stop near, but outside, a named place's radius.
    location = (52.25645, 21.0455)
    track(9100001, at(12, 20), at(13), "DRIVING", [(52.234,21.012),(52.247,21.018),(52.253,21.041),location])
    track(9100002, at(13,12), at(13,25), "WALKING", [location,(52.2565,21.048),(52.253,21.049)])
    stay(9100001,9100002,9300010,at(13),at(13,12),location)
    review("near-place","PLACE_ASSIGNMENT",9100001,9100002,at(13),at(13,12),"unnamed_visit_near_place","9200001")

    # Unnamed stop following a long journey: the opening map must still frame the stop.
    location = (52.25255,21.05234)
    track(9100003,at(14),at(15),"DRIVING",[(52.12,20.91),(52.18,20.98),(52.23,21.02),(52.25255,21.048),location])
    track(9100004,at(15,15),at(15,25),"WALKING",[location,(52.253,21.054),(52.254,21.054)])
    stay(9100003,9100004,9300011,at(15),at(15,15),location)
    review("new-place","PLACE_ASSIGNMENT",9100003,9100004,at(15),at(15,15),"unnamed_visit_new_place")

    # Six-minute hole; two accurate stationary fixes support the place alternative.
    location = (52.2566,21.0425)
    track(9100005,at(16),at(16,5),"DRIVING",[(52.25,21.035),(52.254,21.04),location])
    track(9100006,at(16,11),at(16,20),"DRIVING",[(52.2566,21.0454),(52.257,21.048),(52.26,21.05)])
    insert("derived_intervals",type="GAP",start=at(16,5),endedAt=at(16,11),afterTrackId=9100005,
           reason="MOVED_UNRECORDED",fromLat=location[0],fromLon=location[1],toLat=52.2566,toLon=21.0454)
    for index in range(2):
        insert("engine_observations",id=f"review-demo-fix-{index}",sessionId="review-demo",bootEpochId="review-demo",
               wallTimeMs=at(16,5)+index*46000,elapsedRealtimeNanos=index*46000000000,source="RAW_GNSS",provenance="LIVE",
               latitudeDegrees=location[0],longitudeDegrees=location[1],horizontalAccuracyMeters=5.,qualityFlags=0)
    review("missing","MISSING_RECORDING",9100005,9100006,at(16,5),at(16,11),
           "moved_unrecorded_gap_with_place_evidence","9200002","DRIVING")

    # A misclassified route with an explicit measurement gap, for transport and geometry QA.
    track(9100007,at(17),at(17,22),"WALKING",[(52.245,21.015),(52.249,21.025),(52.253,21.04),(52.255,21.05)],break_at=24)
    review("transport","TRANSPORT",9100007,None,at(17),at(17,22),"personal_classifier_suggestion","CYCLING","WALKING")
    connection.commit()
    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    connection.close()
    adb("push",str(database),"/data/local/tmp/breadcrumb-review-demo.db")
    adb("shell","run-as",PACKAGE,"cp","/data/local/tmp/breadcrumb-review-demo.db","databases/tracks.db")
    adb("shell","run-as",PACKAGE,"rm","-f","databases/tracks.db-wal","databases/tracks.db-shm")
    adb("shell","rm","/data/local/tmp/breadcrumb-review-demo.db")
    print(json.dumps({"serial":args.serial,"backup":str(folder/"before-fixtures.db"),"scenarios":4,"day":args.day}))


if __name__ == "__main__":
    main()
