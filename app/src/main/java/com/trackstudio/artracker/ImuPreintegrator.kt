package com.trackstudio.artracker

/**
 * Timestamp-aware first-stage IMU integration.
 * The low-pass gravity estimate is only a prototype approximation in device coordinates;
 * production VIO must rotate gravity through the estimated attitude and estimate biases.
 */
internal class ImuPreintegrator(
    private val maxExtrapolationNs: Long = 50_000_000L,
    private val historyNs: Long = 2_000_000_000L,
    private val maxSamples: Int = 4096,
    private val gravityFilterTimeConstantSeconds: Double = 0.8
) {
    data class Delta(val valid: Boolean, val rotationXRad: Double = 0.0, val rotationYRad: Double = 0.0, val rotationZRad: Double = 0.0, val intervalSeconds: Double = 0.0, val sampleCount: Int = 0)
    data class AccelerationDelta(val valid: Boolean, val velocityX: Double = 0.0, val velocityY: Double = 0.0, val velocityZ: Double = 0.0, val positionX: Double = 0.0, val positionY: Double = 0.0, val positionZ: Double = 0.0, val intervalSeconds: Double = 0.0, val sampleCount: Int = 0)
    data class GravityEstimate(val valid: Boolean, val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)
    private data class Sample(val timestampNs: Long, val x: Double, val y: Double, val z: Double)
    private val gyroSamples = ArrayDeque<Sample>()
    private val accelerationSamples = ArrayDeque<Sample>()
    private var gravityInitialized = false
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0
    private var lastAccelerationTimestampNs = 0L

    @Synchronized fun reset() {
        gyroSamples.clear(); accelerationSamples.clear(); gravityInitialized = false
        gravityX = 0.0; gravityY = 0.0; gravityZ = 0.0; lastAccelerationTimestampNs = 0L
    }
    @Synchronized fun addGyroscope(timestampNs: Long, x: Float, y: Float, z: Float) = addSample(gyroSamples, timestampNs, x, y, z)
    @Synchronized fun addAccelerometer(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (timestampNs <= 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val last = accelerationSamples.lastOrNull()
        if (last != null && timestampNs <= last.timestampNs) return
        val ax=x.toDouble(); val ay=y.toDouble(); val az=z.toDouble()
        if (!gravityInitialized) { gravityX=ax; gravityY=ay; gravityZ=az; gravityInitialized=true }
        else if (lastAccelerationTimestampNs > 0L) {
            val dt=(timestampNs-lastAccelerationTimestampNs)/1_000_000_000.0
            if (dt > 0.0 && dt < 0.25) {
                val alpha=(dt/(gravityFilterTimeConstantSeconds+dt)).coerceIn(0.0,1.0)
                gravityX += alpha*(ax-gravityX); gravityY += alpha*(ay-gravityY); gravityZ += alpha*(az-gravityZ)
            }
        }
        lastAccelerationTimestampNs=timestampNs
        accelerationSamples.addLast(Sample(timestampNs,ax-gravityX,ay-gravityY,az-gravityZ))
        prune(accelerationSamples,timestampNs)
    }
    @Synchronized fun gravityEstimate() = if (gravityInitialized) GravityEstimate(true,gravityX,gravityY,gravityZ) else GravityEstimate(false)
    @Synchronized fun integrate(startNs: Long,endNs: Long): Delta {
        val points=intervalSamples(gyroSamples,startNs,endNs) ?: return Delta(false)
        var rx=0.0; var ry=0.0; var rz=0.0
        for(i in 1 until points.size) {
            val a=points[i-1]; val b=points[i]; val dt=(b.timestampNs-a.timestampNs)/1_000_000_000.0
            if(dt<=0.0 || dt>maxExtrapolationNs/1_000_000_000.0*2.0) return Delta(false)
            rx+=(a.x+b.x)*0.5*dt; ry+=(a.y+b.y)*0.5*dt; rz+=(a.z+b.z)*0.5*dt
        }
        return Delta(true,rx,ry,rz,(endNs-startNs)/1_000_000_000.0,points.size-1)
    }
    @Synchronized fun integrateAcceleration(startNs: Long,endNs: Long): AccelerationDelta {
        val points=intervalSamples(accelerationSamples,startNs,endNs) ?: return AccelerationDelta(false)
        var vx=0.0; var vy=0.0; var vz=0.0; var px=0.0; var py=0.0; var pz=0.0
        for(i in 1 until points.size) {
            val a=points[i-1]; val b=points[i]; val dt=(b.timestampNs-a.timestampNs)/1_000_000_000.0
            if(dt<=0.0 || dt>maxExtrapolationNs/1_000_000_000.0*2.0) return AccelerationDelta(false)
            val ax=(a.x+b.x)*0.5; val ay=(a.y+b.y)*0.5; val az=(a.z+b.z)*0.5
            px+=vx*dt+0.5*ax*dt*dt; py+=vy*dt+0.5*ay*dt*dt; pz+=vz*dt+0.5*az*dt*dt
            vx+=ax*dt; vy+=ay*dt; vz+=az*dt
        }
        return AccelerationDelta(true,vx,vy,vz,px,py,pz,(endNs-startNs)/1_000_000_000.0,points.size-1)
    }
    private fun addSample(target:ArrayDeque<Sample>,timestampNs:Long,x:Float,y:Float,z:Float) {
        if(timestampNs<=0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val last=target.lastOrNull(); if(last!=null && timestampNs<=last.timestampNs) return
        target.addLast(Sample(timestampNs,x.toDouble(),y.toDouble(),z.toDouble())); prune(target,timestampNs)
    }
    private fun prune(target:ArrayDeque<Sample>,timestampNs:Long) {
        val cutoff=timestampNs-historyNs
        while(target.size>2 && target.first().timestampNs<cutoff) target.removeFirst()
        while(target.size>maxSamples) target.removeFirst()
    }
    private fun intervalSamples(source:ArrayDeque<Sample>,startNs:Long,endNs:Long):List<Sample>? {
        if(startNs<=0L || endNs<=startNs || source.size<2) return null
        val all=source.toList(); val beforeStartIndex=all.indexOfLast{it.timestampNs<=startNs}
        if(beforeStartIndex<0) return null
        val beforeStart=all[beforeStartIndex]; val afterStart=all.getOrNull(beforeStartIndex+1)
        val start=when { beforeStart.timestampNs==startNs -> beforeStart; afterStart!=null -> interpolate(beforeStart,afterStart,startNs); else -> return null }
        val points=ArrayList<Sample>(); points.add(start); all.filterTo(points){it.timestampNs>startNs && it.timestampNs<endNs}
        val beforeEndIndex=all.indexOfLast{it.timestampNs<=endNs}; if(beforeEndIndex<0) return null
        val beforeEnd=all[beforeEndIndex]; val afterEnd=all.getOrNull(beforeEndIndex+1)
        val end=when { beforeEnd.timestampNs==endNs -> beforeEnd; afterEnd!=null -> interpolate(beforeEnd,afterEnd,endNs); endNs-beforeEnd.timestampNs<=maxExtrapolationNs -> beforeEnd.copy(timestampNs=endNs); else -> return null }
        if(end.timestampNs>points.last().timestampNs) points.add(end)
        return if(points.size>=2) points else null
    }
    private fun interpolate(a:Sample,b:Sample,timestampNs:Long):Sample {
        val span=(b.timestampNs-a.timestampNs).toDouble(); if(span<=0.0) return a.copy(timestampNs=timestampNs)
        val t=((timestampNs-a.timestampNs)/span).coerceIn(0.0,1.0)
        return Sample(timestampNs,a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)
    }
}
