package com.example.dashcam.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationPointDao {
    @Insert suspend fun insertAll(points: List<LocationPointEntity>)

    @Query("SELECT * FROM location_points WHERE recordingUuid = :recordingUuid ORDER BY recordedAt ASC, id ASC")
    suspend fun forRecording(recordingUuid: String): List<LocationPointEntity>

    @Query("DELETE FROM location_points WHERE recordingUuid = :recordingUuid")
    suspend fun deleteForRecording(recordingUuid: String)
}
