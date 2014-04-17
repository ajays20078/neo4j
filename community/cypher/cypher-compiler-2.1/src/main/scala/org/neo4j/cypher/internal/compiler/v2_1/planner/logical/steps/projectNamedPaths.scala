/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.{CandidateList, LogicalPlanContext, PlanTable, CandidateGenerator}
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans.{LogicalPlan, NamedPath, ProjectNamedPath}

object projectNamedPaths extends CandidateGenerator[PlanTable] {
  def apply(input: PlanTable)(implicit context: LogicalPlanContext): CandidateList =
    CandidateList(
      for {
        plan <- input.plans
        namedPath <- context.queryGraph.namedPaths if applicable(namedPath, plan)
      }
        yield ProjectNamedPath(namedPath, plan)
    )

  private def applicable(namedPath: NamedPath, plan: LogicalPlan) = {
    val coveredIds = plan.coveredIds

    def wasProjected = coveredIds(namedPath.name)
    def dependenciesFulfilled = (namedPath.dependencies -- coveredIds).isEmpty

    !wasProjected && dependenciesFulfilled
  }
}
