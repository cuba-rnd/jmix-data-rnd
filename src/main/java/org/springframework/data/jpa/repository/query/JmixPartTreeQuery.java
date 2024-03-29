package org.springframework.data.jpa.repository.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.lang.Nullable;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import java.util.List;
import java.util.Optional;

public class JmixPartTreeQuery extends AbstractJpaQuery {

    private static final Logger log = LoggerFactory.getLogger(JmixPartTreeQuery.class);

    private final PartTree tree;
    private final JpaParameters parameters;

    private final QueryPreparer query;
    private final QueryPreparer countQuery;
    private final EscapeCharacter escape;

    /**
     * Creates a new {@link PartTreeJpaQuery}.
     *
     * @param method              must not be {@literal null}.
     * @param em                  must not be {@literal null}.
     * @param persistenceProvider must not be {@literal null}.
     * @param escape
     */

    public JmixPartTreeQuery(JpaQueryMethod method, EntityManager em,
                             PersistenceProvider persistenceProvider, EscapeCharacter escape) {
        super(method, em);

        this.escape = escape;
        Class<?> domainClass = method.getEntityInformation().getJavaType();
        this.parameters = method.getParameters();

        boolean recreationRequired = parameters.hasDynamicProjection() || parameters.potentiallySortsDynamically();

        try {

            this.tree = new PartTree(method.getName(), domainClass);
            this.countQuery = new CountQueryPreparer(persistenceProvider, recreationRequired);
            this.query = tree.isCountProjection() ? countQuery : new QueryPreparer(persistenceProvider, recreationRequired);

        } catch (Exception o_O) {
            throw new IllegalArgumentException(
                    String.format("Failed to create query for method %s! %s", method, o_O.getMessage()), o_O);
        }
    }

    @Override
    public Query doCreateQuery(Object[] values) {
        return query.createQuery(values);
    }


    @Override
    @SuppressWarnings("unchecked")
    public TypedQuery<Long> doCreateCountQuery(Object[] values) {
        return (TypedQuery<Long>) countQuery.createQuery(values);
    }


    /**
     * Query preparer to create {@link CriteriaQuery} instances and potentially cache them.
     *
     * @author Oliver Gierke
     * @author Thomas Darimont
     */
    private class QueryPreparer {

        private final @Nullable
        CriteriaQuery<?> cachedCriteriaQuery;
        private final @Nullable
        ParameterBinder cachedParameterBinder;
        private final PersistenceProvider persistenceProvider;

        QueryPreparer(PersistenceProvider persistenceProvider, boolean recreateQueries) {

            this.persistenceProvider = persistenceProvider;

            JmixJpaQueryCreator creator = createCreator(persistenceProvider, Optional.empty());

            List<ParameterMetadataProvider.ParameterMetadata<?>> expressions;
            if (recreateQueries) {
                this.cachedCriteriaQuery = null;
                this.cachedParameterBinder = null;
            } else {
                this.cachedCriteriaQuery = creator.createQuery();
                expressions = creator.getParameterExpressions();
                this.cachedParameterBinder = getBinder(expressions);
            }
        }

        /**
         * Creates a new {@link Query} for the given parameter values.
         */
        public Query createQuery(Object[] values) {

            CriteriaQuery<?> criteriaQuery = cachedCriteriaQuery;
            ParameterBinder parameterBinder = cachedParameterBinder;
            ParametersParameterAccessor accessor = new ParametersParameterAccessor(parameters, values);

            if (cachedCriteriaQuery == null || accessor.hasBindableNullValue()) {
                JmixJpaQueryCreator creator = createCreator(persistenceProvider, Optional.of(accessor));
                criteriaQuery = creator.createQuery(getDynamicSort(values));
                List<ParameterMetadataProvider.ParameterMetadata<?>> expressions = creator.getParameterExpressions();
                parameterBinder = getBinder(expressions);
            }

            if (parameterBinder == null) {
                throw new IllegalStateException("ParameterBinder is null!");
            }

            return restrictMaxResultsIfNecessary(invokeBinding(parameterBinder, createQuery(criteriaQuery), values));
        }

        /**
         * Restricts the max results of the given {@link Query} if the current {@code tree} marks this {@code query} as
         * limited.
         */
        private Query restrictMaxResultsIfNecessary(Query query) {

            if (tree.isLimiting()) {

                if (query.getMaxResults() != Integer.MAX_VALUE) {
                    /*
                     * In order to return the correct results, we have to adjust the first result offset to be returned if:
                     * - a Pageable parameter is present
                     * - AND the requested page number > 0
                     * - AND the requested page size was bigger than the derived result limitation via the First/Top keyword.
                     */
                    if (query.getMaxResults() > tree.getMaxResults() && query.getFirstResult() > 0) {
                        query.setFirstResult(query.getFirstResult() - (query.getMaxResults() - tree.getMaxResults()));
                    }
                }

                query.setMaxResults(tree.getMaxResults());
            }

            if (tree.isExistsProjection()) {
                query.setMaxResults(1);
            }

            return query;
        }

        /**
         * Checks whether we are working with a cached {@link CriteriaQuery} and synchronizes the creation of a
         * {@link TypedQuery} instance from it. This is due to non-thread-safety in the {@link CriteriaQuery} implementation
         * of some persistence providers (i.e. Hibernate in this case), see DATAJPA-396.
         *
         * @param criteriaQuery must not be {@literal null}.
         */
        private TypedQuery<?> createQuery(CriteriaQuery<?> criteriaQuery) {

            TypedQuery<?> result;

             log.info(criteriaQuery.getRestriction().getExpressions().toString());

            if (this.cachedCriteriaQuery != null) {
                synchronized (this.cachedCriteriaQuery) {
                    result = getEntityManager().createQuery(criteriaQuery);
                }
            } else {
                result = getEntityManager().createQuery(criteriaQuery);
            }

            return result;

        }

        protected JmixJpaQueryCreator createCreator(PersistenceProvider persistenceProvider,
                                                Optional<ParametersParameterAccessor> accessor) {

            EntityManager entityManager = getEntityManager();
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();

            ParameterMetadataProvider provider = accessor
                    .map(it -> new ParameterMetadataProvider(builder, it, persistenceProvider, escape))//
                    .orElseGet(() -> new ParameterMetadataProvider(builder, parameters, persistenceProvider, escape));

            ResultProcessor processor = getQueryMethod().getResultProcessor();

            ReturnedType returnedType = accessor.map(processor::withDynamicProjection)//
                    .orElse(processor).getReturnedType();

            return new JmixJpaQueryCreator(tree, returnedType, builder, provider);
        }

        /**
         * Invokes parameter binding on the given {@link TypedQuery}.
         */
        protected Query invokeBinding(ParameterBinder binder, TypedQuery<?> query, Object[] values) {
            return binder.bindAndPrepare(query, values);
        }

        private ParameterBinder getBinder(List<ParameterMetadataProvider.ParameterMetadata<?>> expressions) {
            return ParameterBinderFactory.createCriteriaBinder(parameters, expressions);
        }

        private Sort getDynamicSort(Object[] values) {

            return parameters.potentiallySortsDynamically() //
                    ? new ParametersParameterAccessor(parameters, values).getSort() //
                    : Sort.unsorted();
        }
    }

    /**
     * Special {@link QueryPreparer} to create count queries.
     *
     * @author Oliver Gierke
     * @author Thomas Darimont
     */
    private class CountQueryPreparer extends QueryPreparer {

        CountQueryPreparer(PersistenceProvider persistenceProvider, boolean recreateQueries) {
            super(persistenceProvider, recreateQueries);
        }

        /*
         * (non-Javadoc)
         * @see org.springframework.data.jpa.repository.query.PartTreeJpaQuery.QueryPreparer#createCreator(org.springframework.data.repository.query.ParametersParameterAccessor, org.springframework.data.jpa.provider.PersistenceProvider)
         */
        @Override
        protected JmixJpaQueryCreator createCreator(PersistenceProvider persistenceProvider,
                                                Optional<ParametersParameterAccessor> accessor) {

            EntityManager entityManager = getEntityManager();
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();

            ParameterMetadataProvider provider = accessor
                    .map(it -> new ParameterMetadataProvider(builder, it, persistenceProvider, escape))//
                    .orElseGet(() -> new ParameterMetadataProvider(builder, parameters, persistenceProvider, escape));

            return new JmixJpaCountQueryCreator(tree, getQueryMethod().getResultProcessor().getReturnedType(), builder, provider);
        }

        /**
         * Customizes binding by skipping the pagination.
         *
         * @see QueryPreparer#invokeBinding(ParameterBinder, TypedQuery, Object[])
         */
        @Override
        protected Query invokeBinding(ParameterBinder binder, TypedQuery<?> query, Object[] values) {
            return binder.bind(query, values);
        }
    }


}
