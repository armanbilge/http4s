package org.http4s.sbt

import sbt._
import sbtcrossproject._
import org.scalajs.sbtplugin.ScalaJSPlugin

object JSPlatformsPlugin extends AutoPlugin {

  object autoImport {
    val NodeJSPlatform = org.http4s.sbt.NodeJSPlatform
    val JSDomPlatform = org.http4s.sbt.JSDomPlatform

    implicit class NodeJSCrossProjectOps(project: CrossProject) {
      def nodeJS: Project = project.projects(JSPlatform)

      def nodeJSSettings(ss: Def.SettingsDefinition*): CrossProject =
        nodeJSConfigure(_.settings(ss: _*))

      def nodeJSConfigure(transformer: Project => Project): CrossProject =
        project
          .configurePlatform(NodeJSPlatform)(transformer)
    }

    implicit class JSCrossProjectOps(project: CrossProject) {
      def jsDOM: Project = project.projects(JSPlatform)

      def jsSettings(ss: Def.SettingsDefinition*): CrossProject =
        jsConfigure(_.settings(ss: _*))

      def jsEnablePlugins(plugins: Plugins*): CrossProject =
        jsConfigure(_.enablePlugins(plugins: _*))

      def jsConfigure(transformer: Project => Project): CrossProject =
        project
          .configurePlatform(NodeJSPlatform)(transformer)
          .configurePlatform(JSDomPlatform)(transformer)
    }

    implicit class JSCrossProjectOps(project: CrossProject) {
      def js: Project = project.projects(JSPlatform)

      def jsSettings(ss: Def.SettingsDefinition*): CrossProject =
        jsConfigure(_.settings(ss: _*))

      def jsEnablePlugins(plugins: Plugins*): CrossProject =
        jsConfigure(_.enablePlugins(plugins: _*))

      def jsConfigure(transformer: Project => Project): CrossProject =
        project
          .configurePlatform(NodeJSPlatform)(transformer)
          .configurePlatform(JSDomPlatform)(transformer)
    }

  }

}

case object NodeJSPlatform extends Platform {
  def identifier: String = "node-js"
  def sbtSuffix: String = "NodeJS"
  def enable(project: Project): Project = project.enablePlugins(ScalaJSPlugin)
}

case object JSDOMPlatform extends Platform {
  def identifier: String = "js-dom"
  def sbtSuffix: String = "JSDOM"
  def enable(project: Project): Project = project.enablePlugins(ScalaJSPlugin)
}
