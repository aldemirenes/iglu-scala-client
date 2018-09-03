/*
 * Copyright (c) 2014 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.iglu.client
package repositories

// Cats
import cats.syntax.apply._
import cats.data.Validated.{Invalid, Valid}

// circe
import io.circe.Json
import io.circe.optics.JsonPath._

// This project
import validation.ProcessingMessageMethods._
import utils.JacksonCatsUtils._
import validation.ProcessingMessage

/**
 * Singleton object contains a constructor
 * from a json.
 */
object RepositoryRefConfig {

  /**
   * Parses the standard repository ref configuration
   * out of the supplied JSON.
   *
   * @param config The JSON containing our repository
   *        ref configuration
   * @return either the RepositoryRefConfig on Success,
   *         or a Nel of ProcessingMessages on Failure.
   */
  def parse(config: Json): ValidatedNelType[RepositoryRefConfig] =
    (
      root.name.string
        .getOption(config)
        .toValidNel(s"Could not retrieve field 'name'".toProcessingMessage),
      root.priority.int
        .getOption(config)
        .toValidNel(s"Could not retrieve field 'priority'".toProcessingMessage),
      root.vendorPrefixes.each.string
        .getAll(config)
        .validNel
        .andThen(
          list =>
            if (list.isEmpty)
              s"Could not retrieve field 'vendorPrefixes'".toProcessingMessage.invalidNel
            else list.validNel)
    ).mapN(RepositoryRefConfig(_, _, _))
}

/**
 * Common config for RepositoryRef classes.
 */
case class RepositoryRefConfig(
  name: String,
  instancePriority: Int,
  vendorPrefixes: List[String]
)

/**
 * Common behavior for all RepositoryRef classes.
 */
trait RepositoryRef {

  /**
   * Our configuration for this RepositoryRef
   */
  val config: RepositoryRefConfig

  /**
   * All repositories with a
   * search priority of 1 will be checked before
   * any repository with a search priority of 2.
   */
  val classPriority: Int

  /**
   * Human-readable descriptor for this
   * type of repository ref.
   */
  val descriptor: String

  /**
   * Abstract method. Provide a concrete
   * implementation for how to lookup a schema
   * in this type of repository.
   *
   * @param schemaKey The SchemaKey uniquely identifying
   *        the schema in Iglu
   * @return a Validation boxing either the Schema's
   *         Json on Success, or an error String
   *         on Failure
   */
  def lookupSchema(schemaKey: SchemaKey): ValidatedType[Option[Json]]

  /**
   * Retrieves an IgluSchema from the Iglu Repo as
   * a Json. Unsafe - only use when you know the
   * schema is available locally.
   *
   * @param schemaKey The SchemaKey uniquely identifies
   *        the schema in Iglu
   * @return the Json representing this schema
   */
  def unsafeLookupSchema(schemaKey: SchemaKey): Json = {
    def exception(msg: ProcessingMessage) =
      new RuntimeException(
        s"Unsafe lookup of schema ${schemaKey} in ${descriptor} Iglu repository ${config.name} failed: ${msg}")

    lookupSchema(schemaKey) match {
      case Valid(Some(schema)) => schema
      case Valid(None)         => throw exception("not found".toProcessingMessage)
      case Invalid(err)        => throw exception(err)
    }
  }

  /**
   * Helper to check if this repository should take
   * priority because of a vendor prefix match. Returns
   * true if we matched our schema's vendor in the
   * list of vendor prefixes.
   *
   * @param schemaKey The SchemaKey uniquely identifying
   *        the schema in Iglu. We will use the vendor
   *        within the SchemaKey to match against our
   *        prefixes
   * @return whether this is a priority lookup or
   *         not
   */
  def vendorMatched(schemaKey: SchemaKey): Boolean = {
    val matches = for {
      p <- config.vendorPrefixes
      m = schemaKey.vendor.startsWith(p)
    } yield m
    matches.foldLeft(false)(_ || _) // True if any match
  }
}
