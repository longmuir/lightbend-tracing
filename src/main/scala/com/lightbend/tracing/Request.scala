package com.lightbend.tracing

import java.util.UUID

import akka.actor.{ActorLogging, Props, ActorRef, Actor}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, Publish}
import akka.cluster.sharding.ShardRegion.MessageExtractor
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton.{ClusterSingletonProxySettings, ClusterSingletonProxy, ClusterSingletonManager}
import org.slf4j.MDC

object Request {
  case class BeginRequest(requestId: UUID)
  case class RequestCompleted(requestId: UUID)

  def props(): Props = Props(new Request())
}

class Request() extends Actor with ActorLogging {
  import Request._

  val requestId = UUID.fromString(self.path.name)

  MDC.put("requestId", requestId.toString)

  private val mediator = DistributedPubSub(context.system).mediator
  private val orderManagement = createOrderManagement()
  private val validation = createValidation()
  private val paymentProcessor = createPaymentProcessor()
  private val shipping = createShipping()

  // This uses Cluster Singleton to verify whether or not MDC will transfer
  // to a singleton.
  protected def createOrderManagement() = {
    context.actorOf(ClusterSingletonProxy.props(
      "/user/order-management",
      ClusterSingletonProxySettings(context.system)
    ))
  }

  // This actor will use a pub/sub model to verify if MDC will transfer
  // over distributed pub/sub
  protected def createValidation() = {
    val validations = context.actorOf(Validation.props(), "validation")

    mediator ! Subscribe(s"Validations$requestId", validations)

    validations
  }

  // This uses a local actor to verify whether or not MDC will transfer locally.
  protected def createPaymentProcessor() = {
    context.actorOf(PaymentProcessor.props(), "payment-processor")
  }

  // This uses Cluster Sharding to verify whether or not MDC will transfer
  // to a sharded actor.
  protected def createShipping() = {
    val messageExtractor = new MessageExtractor {
      override def entityId(message: Any): String = message match {
        case Shipping.ShipOrder(orderId) => orderId.toString
      }
      override def shardId(message: Any): String = message match {
        case Shipping.ShipOrder(orderId) => (Math.abs(orderId.hashCode()) % 10).toString
      }
      override def entityMessage(message: Any): Any = message
    }

    ClusterSharding(context.system).start(
      "shipping",
      Shipping.props(),
      ClusterShardingSettings(context.system),
      messageExtractor
    )
  }

  override def receive: Receive = {
    case BeginRequest(_) =>
      log.info("Begin Request")
      MDC.put("requestId", requestId.toString)
      orderManagement ! OrderManagement.CreateOrder
      context.become(creatingOrder(sender()))
  }

  private def creatingOrder(origin: ActorRef): Receive = {
    case OrderManagement.OrderCreated(orderId, amount) =>
      MDC.put("requestId", requestId.toString)
      mediator ! Publish(s"Validations$requestId", Validation.Validate(orderId, amount))
      context.become(validatingOrder(origin))
  }

  private def validatingOrder(origin: ActorRef): Receive = {
    case Validation.Validated(orderId, amount) =>
      MDC.put("requestId", requestId.toString)
      paymentProcessor ! PaymentProcessor.CompletePayment(amount)
      context.become(requestingPayment(origin, orderId))
  }

  private def requestingPayment(origin: ActorRef, orderId: UUID): Receive = {
    case PaymentProcessor.PaymentCompleted(amount) =>
      MDC.put("requestId", requestId.toString)
      shipping ! Shipping.ShipOrder(orderId)
      context.become(shippingOrder(origin))
  }

  private def shippingOrder(origin: ActorRef): Receive = {
    case Shipping.OrderShipped(orderId) =>
      MDC.put("requestId", requestId.toString)
      origin ! RequestCompleted(requestId)
      context.become(receive)
  }
}
