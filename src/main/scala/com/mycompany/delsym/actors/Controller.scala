package com.mycompany.delsym.actors

import scala.concurrent.duration.DurationInt
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.actor.actorRef2Scala
import akka.routing.RoundRobinRouter
import com.mycompany.delsym.daos.MockOutlinkFinder
import com.mycompany.delsym.daos.HtmlOutlinkFinder
import akka.routing.FromConfig
import akka.actor.ActorRef
import akka.routing.Router

class Controller extends Actor with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy(
      maxNrOfRetries = 10,
      withinTimeRange = 1.minute) {
    case _: Exception => SupervisorStrategy.Restart
  }
  
  val reaper = context.actorOf(Props[Reaper], name="reaper")

  val config = ConfigFactory.load()
  val numFetchers = config.getInt("delsym.fetchers.numworkers")
  val numParsers = config.getInt("delsym.parsers.numworkers")
  val numIndexers = config.getInt("delsym.indexers.numworkers")
  
  val testUser = config.getBoolean("delsym.testuser")
  val outlinkFinder = if (testUser) new MockOutlinkFinder()
                      else new HtmlOutlinkFinder()
  
  val queueSizes = scala.collection.mutable.Map[String,Int]()
  
  val fetchers = context.actorOf(Props[FetchWorker]
    .withRouter(RoundRobinRouter(nrOfInstances=numFetchers)), 
    name="fetchers")
  reaper ! Register(fetchers)
  queueSizes += (("fetchers", 0))
  
  val parsers = context.actorOf(Props[ParseWorker]
    .withRouter(RoundRobinRouter(nrOfInstances=numParsers)), 
    name="parsers")
  reaper ! Register(parsers)
  queueSizes += (("parsers", 0))
  
  val indexers = context.actorOf(Props[IndexWorker]
    .withRouter(RoundRobinRouter(nrOfInstances=numIndexers)),
    name="indexers")
  reaper ! Register(indexers)
  queueSizes += (("indexers", 0))

  def receive = {
    case m: Fetch => {
      increment("fetchers")
      fetchers ! m
    }
    case m: FetchComplete => {
      decrement("fetchers")
      if (m.fwd) parsers ! Parse(m.url)
    }
    case m: Parse => {
      increment("parsers")
      parsers ! m
    }
    case m: ParseComplete => {
      decrement("parsers")
      outlinks(m.url).map(outlink => 
        fetchers ! Fetch(outlink._1, outlink._2, outlink._3))
      if (m.fwd) indexers ! Index(m.url)
    }
    case m: Index => {
      increment("indexers")
      indexers ! m
    }
    case m: IndexComplete => {
      decrement("indexers")
    }
    case m: Stats => {
      sender ! queueSize()
    }
    case m: Stop => {
      reaper ! Stop(0)
    }
    case _ => log.info("Unknown message received.")
  }
  
  def queueSize(): Stats = Stats(queueSizes.toMap)
  
  def outlinks(url: String): 
      List[(String,Int,Map[String,String])] = {
    outlinkFinder.findOutlinks(url) match {
      case Right(triples) => triples
      case Left(f) => List.empty
    }
  }
  
  def increment(key: String): Unit = {
    queueSizes += ((key, queueSizes(key) + 1))
  }
  
  def decrement(key: String): Unit = {
    queueSizes += ((key, queueSizes(key) - 1))
  }
}
