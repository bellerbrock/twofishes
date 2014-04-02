package com.foursquare.twofishes

import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.io.{WKBReader, WKBWriter}
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import com.foursquare.geo.shapes.ShapefileS2Util
import com.foursquare.twofishes.util.{GeometryUtils, StoredFeatureId}
import com.google.common.geometry.S2CellId
import scala.collection.mutable.{HashMap, HashSet, ListBuffer}
import akka.actor.{Actor, PoisonPill}
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import akka.actor.ActorSystem
import akka.actor.Props
import akka.routing.RoundRobinRouter
import java.util.concurrent.CountDownLatch
import scalaj.collection.Implicits._
import akka.routing.Broadcast
import java.nio.ByteBuffer
import org.bson.types.ObjectId
import com.mongodb.casbah.Imports._

// ====================
// ===== Messages =====
// ====================
sealed trait CoverMessage
case class Done extends CoverMessage
case class CalculateCover(polyId: ObjectId, geomBytes: Array[Byte]) extends CoverMessage
case class CalculateCoverRange(polyIds: List[ObjectId]) extends CoverMessage

class NullActor extends Actor {
  def receive = {
    case x =>
  }
}

object TerribleCounter {
  var count = 0
}

class RevGeoWorker extends Actor with DurationUtils {
  val minS2Level = 8
  val maxS2Level = 12
  val maxCells = 10000
  val levelMod = 2

  val wkbReader = new WKBReader()
  val wkbWriter = new WKBWriter()

  def calculateCover(msg: CalculateCoverRange) {
    val cursor = PolygonIndexDAO.find(MongoDBObject("_id" -> MongoDBObject("$in" -> msg.polyIds)))
    cursor.foreach(poly => {
      calculateCover(poly._id, poly.polygon)
    })
  }

  def calculateCover(msg: CalculateCover) {
    calculateCover(msg.polyId, msg.geomBytes)
  }

  def calculateCover(polyId: ObjectId, geomBytes: Array[Byte]) {
    logDuration("generated cover for %s".format(polyId)) {
      TerribleCounter.count += 1
      if (TerribleCounter.count % 1000 == 0) {
        logger.info("processed about %s polygons for revgeo coverage".format(TerribleCounter.count))
      }

      val geom = wkbReader.read(geomBytes)
   	
     	println("generating cover for %s".format(polyId))
      val cells = logDuration("generated cover for %s".format(polyId)) {
        GeometryUtils.s2PolygonCovering(
          geom, minS2Level, maxS2Level,
          levelMod = Some(levelMod),
          maxCellsHintWhichMightBeIgnored = Some(1000)
        )
      }

      logDuration("clipped and outputted cover for %d cells (%s)".format(cells.size, polyId)) {
        val recordShape = geom.buffer(0)
  	  val preparedRecordShape = PreparedGeometryFactory.prepare(recordShape)
        val records = cells.asScala.map((cellid: S2CellId) => {
          val s2shape = ShapefileS2Util.fullGeometryForCell(cellid)
          val cellGeometryBuilder = CellGeometry.newBuilder
          if (preparedRecordShape.contains(s2shape)) {    	
    	      RevGeoIndex(cellid.id(), polyId, full = true, geom = None)
          } else {
  	      	RevGeoIndex(
  	      		cellid.id(), polyId,
  	      		full = false,
  	      		geom = Some(wkbWriter.write(s2shape.intersection(recordShape)))
  	      	)
  	      }
        })
        RevGeoIndexDAO.insert(records)
      }
    }
  }

  def receive = {
    case msg: CalculateCover =>
      calculateCover(msg)
    case msg: CalculateCoverRange =>
      calculateCover(msg)
  }
}


// ==================
// ===== Master =====
// ==================
class RevGeoMaster(latch: CountDownLatch) extends Actor {
  var start: Long = 0

  val _system = ActorSystem("RoundRobinRouterExample")
  val router = _system.actorOf(Props[RevGeoWorker].withRouter(RoundRobinRouter(8)), name = "myRoundRobinRouterActor")

  // message handler
  def receive = {
    case msg: CalculateCover =>
	    router ! msg
    case msg: Done =>
      println("all done, sending poison pills")
      // send a PoisonPill to all workers telling them to shut down themselves
      router ! Broadcast(PoisonPill)

      // send a PoisonPill to the router, telling him to shut himself down
      router ! PoisonPill

      context.stop(self)
  }

  override def preStart() {
    start = System.currentTimeMillis
  }

  override def postStop() {
    // tell the world that the calculation is complete
    println(
      "\n\tCalculation time: \t%s millis"
      .format((System.currentTimeMillis - start)))
    latch.countDown()
  }
}
