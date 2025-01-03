/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package scalaguide.detailed.filters

// #filters-combine-enabled-filters
import javax.inject.Inject

import play.filters.cors.CORSFilter
import play.api.http.{ DefaultHttpFilters, EnabledFilters }

class Filters @Inject()(enabledFilters: EnabledFilters, corsFilter: CORSFilter)
  extends DefaultHttpFilters(enabledFilters.filters :+ corsFilter: _*)

// #filters-combine-enabled-filters
