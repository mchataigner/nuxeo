/*
 * (C) Copyright 2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Salem Aouana
 */

package org.nuxeo.elasticsearch.hint;

import org.opensearch.common.geo.ShapeRelation;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.nuxeo.ecm.core.query.sql.model.EsHint;
import org.nuxeo.elasticsearch.api.ESHintQueryBuilder;

/**
 * The implementation of {@link ESHintQueryBuilder} for the <strong>"geo_shape"</strong> Elasticsearch hint operator.
 *
 * @since 11.1
 */
public class GeoShapeESHintQueryBuilder extends AbstractGeoESHintQueryBuilder {

    /**
     * {@inheritDoc}
     * <p>
     *
     * @return {@link org.opensearch.index.query.GeoShapeQueryBuilder}
     */
    @Override
    public QueryBuilder make(EsHint hint, String fieldName, Object value) {
        String[] values = validate(value, 4, "shapeId, type (unused), index and path");
        return QueryBuilders.geoShapeQuery(fieldName, values[0])
                            .relation(ShapeRelation.WITHIN)
                            .indexedShapeIndex(values[2])
                            .indexedShapePath(values[3]);
    }
}
