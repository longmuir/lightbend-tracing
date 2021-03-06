package com.lightbend.tracing

import java.util.UUID

import akka.actor._
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import org.slf4j.MDC

import scala.concurrent.duration._

object Request {
  case class BeginRequest(requestId: UUID)
  case class RequestCompleted(requestId: UUID)

  def props(mediator: ActorRef): Props = Props(new Request(mediator))
}

class Request(mediator: ActorRef) extends Actor with ActorLogging {
  import Request._

  val requestId = UUID.fromString(self.path.name)

  context.setReceiveTimeout(30.seconds)

  override def receive: Actor.Receive = idle

  private def idle: Receive = {
    case BeginRequest(_) =>
      MDC.put("requestId", requestId.toString)
      log.info("Begin Request")
      mediator ! Publish(s"Orders", OrderManagement.CreateOrder, sendOneMessageToEachGroup = true)
      context.become(creatingOrder(sender()))
    case ReceiveTimeout => context.stop(self)
  }

  private def creatingOrder(origin: ActorRef): Receive = {
    case OrderManagement.OrderCreated(orderId, amount) =>
      mediator ! Publish(s"Validations", Validation.Validate(orderId, amount), sendOneMessageToEachGroup = true)
      context.become(validatingOrder(origin))
    case ReceiveTimeout => context.stop(self)
  }

  private def validatingOrder(origin: ActorRef): Receive = {
    case Validation.Validated(orderId, amount) =>
      mediator ! Publish(s"Payments", PaymentProcessor.CompletePayment(amount), sendOneMessageToEachGroup = true)
      context.become(requestingPayment(origin, orderId))
    case ReceiveTimeout => context.stop(self)
  }

  private def requestingPayment(origin: ActorRef, orderId: UUID): Receive = {
    case PaymentProcessor.PaymentCompleted(amount) =>
      mediator ! Publish(s"Shipments", Shipping.ShipOrder(orderId), sendOneMessageToEachGroup = true)
      context.become(shippingOrder(origin))
    case ReceiveTimeout => context.stop(self)
  }

  private def shippingOrder(origin: ActorRef): Receive = {
    case Shipping.OrderShipped(orderId) =>
      log.info("Request Completed")
      origin ! RequestCompleted(requestId)
      context.stop(self)
    case ReceiveTimeout => context.stop(self)
  }
}
