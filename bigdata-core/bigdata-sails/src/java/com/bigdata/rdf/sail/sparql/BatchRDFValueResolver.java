/**

Copyright (C) SYSTAP, LLC 2006-2015.  All rights reserved.

Contact:
     SYSTAP, LLC
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@systap.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/* Portions of this code are:
 *
 * Copyright Aduna (http://www.aduna-software.com/) (c) 1997-2007.
 *
 * Licensed under the Aduna BSD-style license.
 */
/*
 * Created on Aug 24, 2011
 */

package com.bigdata.rdf.sail.sparql;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.openrdf.model.Literal;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.XMLSchema;
import org.openrdf.query.MalformedQueryException;

import com.bigdata.bop.IValueExpression;
import com.bigdata.rdf.internal.DTE;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.IVUtility;
import com.bigdata.rdf.internal.LexiconConfiguration;
import com.bigdata.rdf.internal.VTE;
import com.bigdata.rdf.internal.constraints.SPARQLConstraint;
import com.bigdata.rdf.internal.impl.TermId;
import com.bigdata.rdf.internal.impl.literal.AbstractLiteralIV;
import com.bigdata.rdf.internal.impl.literal.FullyInlineTypedLiteralIV;
import com.bigdata.rdf.model.BigdataLiteral;
import com.bigdata.rdf.model.BigdataURI;
import com.bigdata.rdf.model.BigdataValue;
import com.bigdata.rdf.model.BigdataValueFactory;
import com.bigdata.rdf.model.BigdataValueFactoryImpl;
import com.bigdata.rdf.sail.BigdataValueReplacer;
import com.bigdata.rdf.sail.sparql.ast.ASTBlankNode;
import com.bigdata.rdf.sail.sparql.ast.ASTDatasetClause;
import com.bigdata.rdf.sail.sparql.ast.ASTFalse;
import com.bigdata.rdf.sail.sparql.ast.ASTIRI;
import com.bigdata.rdf.sail.sparql.ast.ASTNumericLiteral;
import com.bigdata.rdf.sail.sparql.ast.ASTOperationContainer;
import com.bigdata.rdf.sail.sparql.ast.ASTQName;
import com.bigdata.rdf.sail.sparql.ast.ASTRDFLiteral;
import com.bigdata.rdf.sail.sparql.ast.ASTRDFValue;
import com.bigdata.rdf.sail.sparql.ast.ASTString;
import com.bigdata.rdf.sail.sparql.ast.ASTTrue;
import com.bigdata.rdf.sail.sparql.ast.VisitorException;
import com.bigdata.rdf.sparql.ast.QueryRoot;
import com.bigdata.rdf.store.BD;

/**
 * Class performs efficient batch resolution of RDF Values against the database.
 * This efficiency is important on a cluster and when a SPARQL query or update
 * contains a large number of RDF Values.
 * <p>
 * Note: The {@link PrefixDeclProcessor} will rewrite {@link ASTQName} nodes as
 * {@link ASTIRI} nodes. It MUST run before this processor.
 * <p>
 * Note: Any {@link ASTRDFLiteral} or {@link ASTIRI} nodes are annotated by this
 * processor using {@link ASTRDFValue#setRDFValue(Value)}. This includes IRIrefs
 * in the {@link ASTDatasetClause}, which are matched as either {@link ASTIRI}
 * or {@link ASTQName}.
 * <p>
 * Note: This replaces the functionality of the {@link BigdataValueReplacer}.
 * <p>
 * Note: {@link IValueExpression} nodes used in {@link SPARQLConstraint}s are
 * allowed to use values not actually in the database. MP
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * @openrdf
 */
public class BatchRDFValueResolver extends ASTVisitorBase {

    private final static Logger log = Logger
            .getLogger(BatchRDFValueResolver.class);

    private final boolean readOnly;
    
    private BigdataValueFactory valueFactory;

    private final LinkedHashMap<ASTRDFValue, Value> nodes;

    private Map<Value, BigdataValue> vocab;

    /**
     * @param context
     * @param readOnly
     *            When <code>true</code>, unknown RDF {@link Value}s are not
     *            recorded in the database.
     */
    public BatchRDFValueResolver(final boolean readOnly) {

        this.readOnly = readOnly;
        
        this.valueFactory = BigdataValueFactoryImpl.getInstance("");
        
        this.nodes = new LinkedHashMap<>();
        
        this.vocab = new HashMap<>();

    }
    
    public Map<ASTRDFValue, Value> getNodes() {
        return nodes;
    }

    /**
     * Visit the parse tree, locating and collecting references to all
     * {@link ASTRDFValue} nodes (including blank nodes iff we are in a told
     * bnodes mode). The {@link ASTRDFValue}s are collected in a {@link Map}
     * which associates each one with a {@link BigdataValue} object which is set
     * using {@link ASTRDFValue#setRDFValue(org.openrdf.model.Value)}. The
     * {@link BigdataValue}s are then resolved in a batch against the database,
     * obtaining their {@link IVs}.  This has the side-effect of making their
     * {@link IV}s available in the parse tree.
     * 
     * @param qc
     * 
     * @throws MalformedQueryException
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void processOnPrepareEvaluate(final QueryRoot qc, final BigdataASTContext context)
            throws MalformedQueryException {
        
        valueFactory = context.valueFactory;
        

        /*
         * Build up the vocabulary. This is everything in the parse tree plus
         * some key vocabulary items which correspond to syntactic sugar in
         * SPARQL.
         */
        {
            
            final BigdataValueFactory f = context.valueFactory;

            final Map<Value, BigdataValue> vocab = context.vocab;
            this.vocab = vocab;

        // RDF Collection syntactic sugar vocabulary items.
            vocab.put(RDF.FIRST, f.asValue(RDF.FIRST));
            vocab.put(RDF.REST, f.asValue(RDF.REST));
            vocab.put(RDF.NIL, f.asValue(RDF.NIL));
            vocab.put(BD.VIRTUAL_GRAPH, f.asValue(BD.VIRTUAL_GRAPH));

            /*
             * RDF Values actually appearing in the parse tree.
             */
            final Iterator<Value> itr = nodes.values().iterator();
            
            while (itr.hasNext()) {
            
                final Value value = itr.next();
                
                vocab.put(value, (BigdataValue)value);
                
            }
            
        }

        /*
         * Batch resolve the BigdataValue objects against the database. This
         * sets their IVs as a side-effect.
         */
        {

            final BigdataValue[] values = context.vocab.values().toArray(
                    new BigdataValue[context.vocab.values().size()]);

            context.lexicon.addTerms(values, values.length, readOnly);

            // Cache the BigdataValues on the IVs for later
            for (BigdataValue value : context.vocab.values()) {

                final IV iv = value.getIV();

                if (iv == null) {

                    /*
                     * Since the term identifier is NULL this value is not known
                     * to the kb.
                     */

                    if (log.isInfoEnabled())
                        log.info("Not in knowledge base: " + value);

                    /*
                     * Create a dummy iv and cache the unknown value on it so
                     * that it can be used during query evaluation.
                     */
                    final IV dummyIV = TermId.mockIV(VTE.valueOf(value));

                    value.setIV(dummyIV);

                    dummyIV.setValue(value);

                } else {

                    iv.setValue(value);

                }

            }

        }

        /*
         * Set the BigdataValue object on each ASTRDFValue node.
         * 
         * Note: This resolves each Value against the vocabulary cache. This is
         * necessary in case more than one ASTRDFValue instance exists for the
         * same BigdataValue. Otherwise we would fail to have a side-effect on
         * some ASTRDFValue nodes.
         */
        {
            
            final Iterator<Map.Entry<ASTRDFValue, Value>> itr = nodes
                    .entrySet().iterator();

            while (itr.hasNext()) {

                final Map.Entry<ASTRDFValue, Value> e = itr.next();

                final ASTRDFValue node = e.getKey();

                final Value value = e.getValue();

                final BigdataValue resolvedValue = context.vocab.get(value);
                
                node.setRDFValue(resolvedValue);

            }
            
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void process(final ASTOperationContainer qc)
            throws MalformedQueryException {
        
        try {

            /*
             * Collect all ASTRDFValue nodes into a map, paired with
             * BigdataValue objects.
             */
            qc.jjtAccept(new RDFValuePrepareResolver(), null);
            
        } catch (VisitorException e) {
            
            // Turn the exception into a Query exception.
            throw new MalformedQueryException(e);
            
        }

        /*
         * Build up the vocabulary. This is everything in the parse tree plus
         * some key vocabulary items which correspond to syntactic sugar in
         * SPARQL.
         */
        {
            
//            final BigdataValueFactory f = context.valueFactory;

//            final Map<Value, BigdataValue> vocab = new HashMap<>(); // context.vocab;

            /*
             * RDF Values actually appearing in the parse tree.
             */
            final Iterator<Entry<ASTRDFValue, Value>> itr = nodes.entrySet().iterator();

            while (itr.hasNext()) {
            
                final Entry<ASTRDFValue, Value> entry = itr.next();
                ASTRDFValue value = entry.getKey();
                
                IV iv;
                BigdataValue bigdataValue = null;
                if (value.getRDFValue()!=null) {
                    iv = ((BigdataValue)value.getRDFValue()).getIV();
                } else if (value instanceof ASTIRI) {
                    iv = new TermId<BigdataValue>(VTE.URI,0);
                    bigdataValue = valueFactory.createURI(((ASTIRI)value).getValue());
                    iv.setValue(bigdataValue);
                    bigdataValue.setIV(iv);
                } else if (value instanceof ASTRDFLiteral) {
                    ASTRDFLiteral rdfNode = (ASTRDFLiteral) value;
                    String lang = rdfNode.getLang();
                    ASTIRI dataTypeIri = rdfNode.getDatatype();
                    URIImpl dataTypeUri = null;
//                  if (XMLSchema.DATETIME.equals(dataTypeUri)) {
//                      iv = new XSD
//                  } else 
                    DTE dte = null;
                    if (dataTypeIri!=null && dataTypeIri.getValue()!=null) {
                        dataTypeUri = new URIImpl(dataTypeIri.getValue());
                        dte = DTE.valueOf(dataTypeUri);
                    }
                    if (dte!=null) {
                        iv = IVUtility.decode(rdfNode.getLabel().getValue(), dte.name());
                        bigdataValue = getBigdataValue(iv, dte);
                        if (!bigdataValue.stringValue().equals(rdfNode.getLabel().getValue())) {
                            // Data loss could occur if inline IV will be used, as string representation of original value differ from decoded value
                            bigdataValue = valueFactory.createLiteral(rdfNode.getLabel().getValue(), dataTypeUri);
                        }
                    } else { 
                        iv = new TermId<BigdataValue>(VTE.LITERAL,0);
                        if (lang!=null) {
                            bigdataValue = valueFactory.createLiteral(rdfNode.getLabel().getValue(), lang);
                        } else {
                            bigdataValue = valueFactory.createLiteral(rdfNode.getLabel().getValue(), dataTypeUri);
                        }
                        iv.setValue(bigdataValue);
                        bigdataValue.setIV(iv);
//                      iv = new FullyInlineTypedLiteralIV<BigdataLiteral>(rdfNode.getLabel().getValue(), rdfNode.getLang(), dataTypeUri);
                    }
                } else if (value instanceof ASTNumericLiteral) {
                    ASTNumericLiteral rdfNode = (ASTNumericLiteral) value;
                    URI dataTypeUri = rdfNode.getDatatype();
                    DTE dte = DTE.valueOf(dataTypeUri);
                    iv = IVUtility.decode(rdfNode.getValue(), dte.name());
                    bigdataValue = getBigdataValue(iv, dte);
                    if (!bigdataValue.stringValue().equals(rdfNode.getValue())) {
                        // Data loss could occur if inline IV will be used, as string representation of original value differ from decoded value
                        bigdataValue = valueFactory.createLiteral(rdfNode.getValue(), dataTypeUri);
                    }
                } else if (value instanceof ASTTrue) {
                    bigdataValue = valueFactory.createLiteral(true);
                    iv = bigdataValue.getIV();
                } else if (value instanceof ASTFalse) {
                    bigdataValue = valueFactory.createLiteral(false);
                    iv = bigdataValue.getIV();
                } else {
                    iv = new FullyInlineTypedLiteralIV<BigdataLiteral>(value.toString());
                    bigdataValue = iv.getValue();
                }

                if (bigdataValue!=null) {
                    value.setRDFValue(bigdataValue);
                    vocab.put(bigdataValue, bigdataValue);
                }
                
            }
            
        }

        // RDF Collection syntactic sugar vocabulary items.
        vocab.put(RDF.FIRST, valueFactory.asValue(RDF.FIRST));
        vocab.put(RDF.REST, valueFactory.asValue(RDF.REST));
        vocab.put(RDF.NIL, valueFactory.asValue(RDF.NIL));
        vocab.put(BD.VIRTUAL_GRAPH, valueFactory.asValue(BD.VIRTUAL_GRAPH));

        /*
         * Batch resolve the BigdataValue objects against the database. This
         * sets their IVs as a side-effect.
         */
        {

//            final BigdataValue[] values = context.vocab.values().toArray(
//                    new BigdataValue[0]);
//
//            context.lexicon.addTerms(values, values.length, readOnly);

            // Cache the BigdataValues on the IVs for later
            for (BigdataValue value : vocab.values()) {

                final IV iv = value.getIV();

                if (iv == null) {

                    /*
                     * Since the term identifier is NULL this value is not known
                     * to the kb.
                     */

                    if (log.isInfoEnabled())
                        log.info("Not in knowledge base: " + value);

                    /*
                     * Create a dummy iv and cache the unknown value on it so
                     * that it can be used during query evaluation.
                     */
                    final IV dummyIV = TermId.mockIV(VTE.valueOf(value));

                    value.setIV(dummyIV);

                    dummyIV.setValue(value);

                } else {

                    iv.setValue(value);

                }

            }

        }
//
//        /*
//         * Set the BigdataValue object on each ASTRDFValue node.
//         * 
//         * Note: This resolves each Value against the vocabulary cache. This is
//         * necessary in case more than one ASTRDFValue instance exists for the
//         * same BigdataValue. Otherwise we would fail to have a side-effect on
//         * some ASTRDFValue nodes.
//         */
//        {
//            
//            final Iterator<Map.Entry<ASTRDFValue, BigdataValue>> itr = nodes
//                    .entrySet().iterator();
//
//            while (itr.hasNext()) {
//
//                final Map.Entry<ASTRDFValue, BigdataValue> e = itr.next();
//
//                final ASTRDFValue node = e.getKey();
//
//                final BigdataValue value = e.getValue();
//
//                final BigdataValue resolvedValue = context.vocab.get(value);
//                
//                node.setRDFValue(resolvedValue);
//
//            }
//            
//        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private BigdataValue getBigdataValue(IV iv, DTE dte) {
        BigdataValue bigdataValue;
        if (!iv.hasValue() && iv instanceof AbstractLiteralIV) {
            switch(dte) {
            case XSDByte:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).byteValue());
                break;
            case XSDShort:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).shortValue());
                break;
            case XSDInt:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).intValue());
                break;
            case XSDLong:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).longValue());
                break;
            case XSDFloat:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).floatValue());
                break;
            case XSDDouble:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).doubleValue());
                break;
            case XSDBoolean:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).booleanValue());
                break;
            case XSDString:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).stringValue());
                break;
            case XSDInteger:
                bigdataValue = valueFactory.createLiteral(((AbstractLiteralIV)iv).stringValue(), XMLSchema.INTEGER);
                break;
            case XSDDecimal:
                bigdataValue = valueFactory.createLiteral(iv.stringValue(), DTE.XSDDecimal.getDatatypeURI());
                break;
            default:
                throw new RuntimeException("unknown DTE " + dte);
            }
            bigdataValue.setIV(iv);
            iv.setValue(bigdataValue);
        } else {
            bigdataValue = iv.getValue();
        }
        return bigdataValue;
    }

    /**
     * FIXME Should this be using the {@link LexiconConfiguration} to create
     * appropriate inline {@link IV}s when and where appropriate?
     */
    private class RDFValuePrepareResolver extends ASTVisitorBase {

        @Override
        public Object visit(final ASTQName node, final Object data)
                throws VisitorException {

            throw new VisitorException(
                    "QNames must be resolved before resolving RDF Values");

        }

        /**
         * Note: Blank nodes within a QUERY are treated as anonymous variables,
         * even when we are in a told bnodes mode.
         */
        @Override
        public Object visit(final ASTBlankNode node, final Object data)
                throws VisitorException {
            
            throw new VisitorException(
                    "Blank nodes must be replaced with variables before resolving RDF Values");
            
        }

        @Override
        public Void visit(final ASTIRI node, final Object data)
                throws VisitorException {

            try {

                nodes.put(node, valueFactory.createURI(node.getValue()));

                return null;

            } catch (IllegalArgumentException e) {

                // invalid URI
                throw new VisitorException(e.getMessage());

            }

        }

        @Override
        public Void visit(final ASTRDFLiteral node, final Object data)
                throws VisitorException {

            // Note: This is handled by this ASTVisitor (see below in this
            // class).
            final String label = (String) node.getLabel().jjtAccept(this, null);

            final String lang = node.getLang();

            final ASTIRI datatypeNode = node.getDatatype();

            final Literal literal;

            if (datatypeNode != null) {

                final URI datatype;

                try {

                    datatype = valueFactory.createURI(datatypeNode.getValue());

                } catch (IllegalArgumentException e) {

                    // invalid URI
                    throw new VisitorException(e);

                }

                literal = valueFactory.createLiteral(label, datatype);

            } else if (lang != null) {

                literal = valueFactory.createLiteral(label, lang);

            } else {

                literal = valueFactory.createLiteral(label);

            }

            nodes.put(node, literal);

            return null;

        }

        @Override
        public Void visit(final ASTNumericLiteral node, final Object data)
                throws VisitorException {

            nodes.put(
                    node,
                    valueFactory.createLiteral(node.getValue(),
                            node.getDatatype()));

            return null;

        }

        @Override
        public Void visit(final ASTTrue node, final Object data)
                throws VisitorException {

            nodes.put(node, valueFactory.createLiteral(true));

            return null;

        }

        @Override
        public Void visit(final ASTFalse node, final Object data)
                throws VisitorException {

            nodes.put(node, valueFactory.createLiteral(false));

            return null;

        }

        /**
         * Note: This supports the visitor method for a Literal.
         */
        @Override
        public String visit(final ASTString node, final Object data)
                throws VisitorException {

            return node.getValue();

        }

    }

    /**
     * FIXME Should this be using the {@link LexiconConfiguration} to create
     * appropriate inline {@link IV}s when and where appropriate?
     */
    private class RDFValueResolver extends ASTVisitorBase {

        @Override
        public Object visit(final ASTQName node, final Object data)
                throws VisitorException {

            throw new VisitorException(
                    "QNames must be resolved before resolving RDF Values");

        }

        /**
         * Note: Blank nodes within a QUERY are treated as anonymous variables,
         * even when we are in a told bnodes mode.
         */
        @Override
        public Object visit(final ASTBlankNode node, final Object data)
                throws VisitorException {
            
            throw new VisitorException(
                    "Blank nodes must be replaced with variables before resolving RDF Values");
            
        }

        @Override
        public Void visit(final ASTIRI node, final Object data)
                throws VisitorException {

            try {

                nodes.put(node, valueFactory.createURI(node.getValue()));

                return null;

            } catch (IllegalArgumentException e) {

                // invalid URI
                throw new VisitorException(e.getMessage());

            }

        }

        @Override
        public Void visit(final ASTRDFLiteral node, final Object data)
                throws VisitorException {

            // Note: This is handled by this ASTVisitor (see below in this
            // class).
            final String label = (String) node.getLabel().jjtAccept(this, null);

            final String lang = node.getLang();

            final ASTIRI datatypeNode = node.getDatatype();

            final BigdataLiteral literal;

            if (datatypeNode != null) {

                final BigdataURI datatype;

                try {

                    datatype = valueFactory.createURI(datatypeNode.getValue());

                } catch (IllegalArgumentException e) {

                    // invalid URI
                    throw new VisitorException(e);

                }

                literal = valueFactory.createLiteral(label, datatype);

            } else if (lang != null) {

                literal = valueFactory.createLiteral(label, lang);

            } else {

                literal = valueFactory.createLiteral(label);

            }

            nodes.put(node, literal);

            return null;

        }

        @Override
        public Void visit(final ASTNumericLiteral node, final Object data)
                throws VisitorException {

            nodes.put(
                    node,
                    valueFactory.createLiteral(node.getValue(),
                            node.getDatatype()));

            return null;

        }

        @Override
        public Void visit(final ASTTrue node, final Object data)
                throws VisitorException {

            nodes.put(node, valueFactory.createLiteral(true));

            return null;

        }

        @Override
        public Void visit(final ASTFalse node, final Object data)
                throws VisitorException {

            nodes.put(node, valueFactory.createLiteral(false));

            return null;

        }

        /**
         * Note: This supports the visitor method for a Literal.
         */
        @Override
        public String visit(final ASTString node, final Object data)
                throws VisitorException {

            return node.getValue();

        }

    }

    public Map<Value, BigdataValue> getValues() {
        return vocab;
    }

}
