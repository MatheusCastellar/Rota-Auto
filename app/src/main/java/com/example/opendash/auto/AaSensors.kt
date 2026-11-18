package com.example.opendash.auto

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors

/** Manual parking declaration only; never replaces the phone's GPS or speed. */
internal object AaSensors {
    const val PARKED = "aa-manually-parked"
    val supported = listOf(Sensors.SensorType.NIGHT, Sensors.SensorType.PARKING_BRAKE, Sensors.SensorType.DRIVING_STATUS)
    fun event(type:Sensors.SensorType, parked:Boolean,night:Boolean=false):Sensors.SensorBatch? {
        val batch=Sensors.SensorBatch.newBuilder()
        when(type) {
            Sensors.SensorType.NIGHT -> batch.addNightMode(Sensors.SensorBatch.NightData.newBuilder().setIsNightMode(night))
            Sensors.SensorType.PARKING_BRAKE -> batch.addParkingbrakeData(Sensors.SensorBatch.ParkingBrakeData.newBuilder().setIsEngaged(parked))
            Sensors.SensorType.DRIVING_STATUS -> batch.addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
                // Keep voice available while restricting parked-only interactions.
                .setStatus(if(parked) 0 else 1 or 2 or 8 or 16))
            else -> return null
        }
        return batch.build()
    }
}
