package com.rigon.zipkin

import com.twitter.finagle.RequestTimeoutException
import com.twitter.finagle.tracing.{Annotation, Trace}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{Future, Duration}
import com.twitter.util.Duration._
import org.apache.log4j.Logger

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.whitebox
import scala.language.experimental.macros


@compileTimeOnly("traced macro")
class traced(name: String, protocol: String, timeout: Duration) extends StaticAnnotation {
  def macroTransform(annottees: Any*) = macro traceMacro.impl
}

object traceMacro {
  def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._
    val list = c.macroApplication.children.head.children.head.children.tail
    val id = list.head
    val protocol = list.tail.head
    val timeout = list.last

    val fileName = c.enclosingPosition.source.path
    val shortFileName = fileName.substring(fileName.lastIndexOf("/")+1).replace(".scala", "")
    val result = {
      annottees.map(_.tree).toList match {
        case q"$mods def $name[..$params](...$paramss): $returnType = $expr" :: Nil =>
          println(s"[$shortFileName] Method $name: $returnType will be traced.")
          q"""
             $mods def $name[..$params](...$paramss): $returnType = {
              import com.rigon.zipkin.Tracing.withTrace
              withTrace($id, $protocol, $timeout) {
                $expr
              }
            }
          """
      }
    }
    c.Expr[Any](result)
  }
}

object Tracing {

  private val traceLog = Logger.getLogger(getClass)

  def withTrace[T](id: String, protocol: String = "custom", timeout: Duration = fromSeconds(1)) (block: => Future[T]) = {
    Trace.traceService(id, protocol, Option.empty) {
      Trace.record(Annotation.ClientSend())
      val time = System.currentTimeMillis()
      withTimeout(id, timeout, block) map { res =>
        traceLog.info(s"$id API took ${System.currentTimeMillis() - time}ms to respond")
        Trace.record(Annotation.ClientRecv())
        res
      }
    }
  }

  private def withTimeout[T](id: String = s"${getClass.getSimpleName}", duration: Duration, block: => Future[T]): Future[T] = {
    block.within(
    DefaultTimer.twitter,
    duration, {
      Trace.record(s"$id.timeout")
      new RequestTimeoutException(duration, s"Timeout exceed while accessing using $id")
    }
    )
  }
}