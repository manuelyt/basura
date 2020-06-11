package org.easa.eccairs.importpdf.core.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.easa.eccairs.auth.client.EccairsAuthUser;
import org.easa.eccairs.importpdf.core.dto.PersonalDetails;
import org.easa.eccairs.importpdf.core.exception.GetStructureException;
import org.easa.eccairs.importpdf.core.service.PdfParserService;
import org.easa.eccairs.importpdf.core.service.base.PdfParserServiceBase;
import org.easa.eccairs.importpdf.data.model.AttributeModel;
import org.easa.eccairs.importpdf.data.model.PdfFieldConfModel;
import org.easa.eccairs.importpdf.data.model.PdfModel;
import org.easa.eccairs.importpdf.data.repository.AttributeRepository;
import org.easa.eccairs.importpdf.data.repository.PdfFieldConfRepository;
import org.easa.eccairs.importpdf.util.Constants;
import org.easa.eccairs.importpdf.util.TaxNode;
import org.springframework.aop.AopInvocationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PdfParserServiceImpl extends PdfParserServiceBase implements PdfParserService {

    protected final AttributeRepository attributeRepository;

    protected final PdfFieldConfRepository pdfFieldConfRepository;

    private final String temp_folder;

    public PdfParserServiceImpl(
            AttributeRepository attributeRepository,
            PdfFieldConfRepository pdfFieldConfRepository,
            @Value("${temp.folder}") String temp_folder
    ) {
        this.attributeRepository = attributeRepository;
        this.pdfFieldConfRepository = pdfFieldConfRepository;
        this.temp_folder = temp_folder;
    }

    @Override
    public boolean personalDetailsOK(final PersonalDetails personaldetails) {
        final Pattern patternPhone = Pattern.compile("^[+]*[(]{0,1}[0-9]{1,4}[)]{0,1}[-\\s\\./0-9]*$");
        final Pattern patternMail = Pattern.compile("^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+$");

        return Objects.nonNull(personaldetails.getFirstName()) &&
                Objects.nonNull(personaldetails.getLastName()) &&
                Objects.nonNull(personaldetails.getUsername()) &&
                Objects.nonNull(personaldetails.getTelephone()) &&
                Objects.nonNull(personaldetails.getEmail()) &&
                patternPhone.matcher(personaldetails.getTelephone()).matches() &&
                patternMail.matcher(personaldetails.getEmail()).matches();
    }

    @Override
    public boolean readPdf(final PdfModel pdfrepo) throws IOException, SQLException {
        final File outputFile;
        outputFile = ResourceUtils.getFile("classpath:" + this.temp_folder + "file.pdf");
        final Blob blob = pdfrepo.getFile();
        final InputStream is = blob.getBinaryStream();
        final FileOutputStream fout = new FileOutputStream(outputFile);
        IOUtils.copy(is, fout);
        return true;
    }

    @Override
    public String getPdfType(final String nameFilePdf) throws IOException {
        try {
            final PDDocument pdfDocument = PDDocument.load(
                    ResourceUtils.getFile("classpath:" + this.temp_folder + Constants.TEMP_FILE));
            final PDAcroForm acroForm = pdfDocument.getDocumentCatalog().getAcroForm();
            if (Objects.nonNull(acroForm)) {
                return this.getPdfType2(acroForm);
            }
        } catch (final IOException e) {
            log.error(Constants.ERROR_GETTING_FORM_ID, e);
        }
        return "";
    }

    private String getPdfType2(final PDAcroForm acroForm) {
        Integer contT = 0, contC = 0, contB = 0, cont = 0;
        for (final Iterator<PDField> it = acroForm.getFieldIterator(); it.hasNext(); ) {
            final PDField field = it.next();
            if (field.getFullyQualifiedName().startsWith("T")) {
                cont++;
            }
            if (field.getFieldType().equals("Tx")) {
                contT++;
            } else if (field.getFieldType().equals("Ch")) {
                contC++;
            } else if (field.getFieldType().equals("Btn")) {
                contB++;
            }
        }
        if (cont == 40 && contT == 19) {
            return Constants.PDF_TYPE_IND;
        } else if (cont == 200 && contT == 78) {
            return Constants.PDF_TYPE_AER;
        } else if (cont == 250 && contT == 90) {
            return Constants.PDF_TYPE_ATM;
        } else if (cont == 256 && contT == 100) {
            return Constants.PDF_TYPE_FLI;
        } else if (cont == 231 && contT == 122) {
            return Constants.PDF_TYPE_TEC;
        } else if (cont == 63 && contT == 26) {
            return Constants.PDF_TYPE_ORG;
        } else {
            return null;
        }
    }

    @Override
    public Map<String, String> getMapCodeValueIDForm(
            final String nameFilePdf,
            final String pdfType
    ) {
        try {
            final PDDocument pdfDocument = PDDocument.load(
                    ResourceUtils.getFile("classpath:" + this.temp_folder + Constants.TEMP_FILE));
            final PDAcroForm acroForm = pdfDocument.getDocumentCatalog().getAcroForm();
            if (Objects.nonNull(acroForm)) {
                final Map<String, String> codeValuesID = this.getCodeValueIdForm(acroForm, pdfType);
                return codeValuesID;
            }
        } catch (final IOException e) {
            log.error(Constants.ERROR_GETTING_FORM_ID, e);
        }
        return new HashMap<>();
    }


    /*
    en Constants tengo puestos a fuego los campos que me interesan del pdf,
    ( el campo pdf "T1S1_E24-1_DT12_A454-1/L1" "World Region") y que taxonomia es 454
    en codeValuesID recogo todos esos campos de constants

    recorro el pdf, y cuando encuentro alguno que este en mi lista codeValuesID
    lo agnado a codeValueIDForm
     */
    private Map<String, String> getCodeValueIdForm(final PDAcroForm acroForm, final String pdfType) {
        final Map<java.lang.String, java.lang.String> codeValueIDFormAux = new HashMap<java.lang.String, java.lang.String>();
        final Map<String, String> codeValuesID = this.getMapCodeValuesID(pdfType);
        for (final Iterator<PDField> it = acroForm.getFieldIterator(); it.hasNext(); ) {
            final PDField field = it.next();
            if (codeValuesID.containsKey(field.getFullyQualifiedName())) {
                codeValueIDFormAux.put(field.getFullyQualifiedName(), this.getTaxonomyCode(field, codeValuesID));
            }
        }
        return codeValueIDFormAux;
    }

    private Map<String, String> getMapCodeValuesID(final String pdfType) {
        Integer pdft = Integer.parseInt(pdfType);

        final Map<String, String> codeDValuesIDn2 = new HashMap<>();
        final List<AttributeModel> att = this.attributeRepository.findAll();
        final HashMap atthash = new HashMap();
        for (final AttributeModel am : att) {
            atthash.put(am.getId(), am.getTaxonomy_code());
        }
        final List<PdfFieldConfModel> pfc = this.pdfFieldConfRepository.findAll();
        for (final PdfFieldConfModel reg : pfc) {
            if (reg.getPdf_id().equals(pdft)) {
                final Integer tax = (Integer) atthash.get(reg.getAttribute_id());
                codeDValuesIDn2.put(reg.getName(), tax.toString());
            }
        }
        return codeDValuesIDn2;
    }

    @Override
    public Map<String, String> getMapCodeHierarchy(final String pdfType) {
        final Integer pdft = Integer.parseInt(pdfType);
        final Map<String, String> codeDValuesIDn2 = new HashMap<String, String>();
        final List<AttributeModel> att = this.attributeRepository.findAll();
        final HashMap atthash = new HashMap();
        for (final AttributeModel am : att) {
            atthash.put(am.getId(), am.getTaxonomy_code());
        }
        final List<PdfFieldConfModel> pfc = this.pdfFieldConfRepository.findAll();
        for (final PdfFieldConfModel reg : pfc) {
            if (reg.getPdf_id().equals(pdft)) {
                final Integer tax = (Integer) atthash.get(reg.getAttribute_id());
                codeDValuesIDn2.put(tax.toString(), reg.getHierarchy());
            }
        }
        return codeDValuesIDn2;
    }

    @Override
    public TaxNode getStructure(
            final Map<String, String> mapCodes,
            final Map<String, String> mapHierarchy,
            final String father,
            final String pdfType
    ) {
        try {
            String tokant = "", tok = "", lev = "", strucEntry = "";
            final Map<String, String> codeValIDInv = this.getMapCodeValuesIDInverse(pdfType);
            final TaxNode struct = new TaxNode();
            struct.setId("24");
            for (final Map.Entry m : mapHierarchy.entrySet()) {
                if (m.getValue().equals("{\"24\": {}}")) {
                    struct.attr.put((String) m.getKey(), mapCodes.get(codeValIDInv.get(m.getKey())));
                } else if (m.getValue().equals("")) {
                    throw new GetStructureException(" (bbdd data error) hierarchy code for taxonomy : " + m.getKey() + " is empty");
                } else {
                    final StringTokenizer token = new StringTokenizer((String) m.getValue(), ":");
                    tokant = token.nextToken();
                    tok = token.nextToken();
                    strucEntry = "";
                    while (token.hasMoreTokens()) {
                        tokant = tok;
                        tok = token.nextToken();
                        lev = tokant.substring(3, tokant.length() - 1);
                        strucEntry += lev + ",";
                        struct.putAtrr(strucEntry, (String) m.getKey(), mapCodes.get(codeValIDInv.get(m.getKey())));

                    }
                }
                if (mapCodes.get(codeValIDInv.get(m.getKey())).equals("243")) {
                    final Integer kk22 = 0;
                }
                if ((m.getKey()).equals("215")) {
                    final Integer kk22 = 0;
                }
            }
            return struct;
        } catch (final Exception e) {
            throw new GetStructureException("error in getAllChilds");
        }
    }

    private String getTaxonomyCode(final PDField field, final Map<String, String> codeValuesID) {
        String taxonomy_code_value = "";
        String taxonomy_code_value2 = "";
        if (field.getFieldType().equals("Tx")) {
            return field.getValueAsString();
        }
        if (field.getFieldType().equals("Ch")) {
            if (field.getValueAsString().length() > 2) {
                taxonomy_code_value = field.getValueAsString().substring(1, field.getValueAsString().length() - 1);
                // como en algunos combos en el valor devolvia String, si no es un numero, los convierto a su codigo
                // CHECK NumberUtils
                //if (NumberUtils.isParsable(taxonomy_code_value)) {
                //   return taxonomy_code_value;
                //} else {
                taxonomy_code_value2 = this.convertPdfTextToCode(taxonomy_code_value, field, Integer.valueOf(codeValuesID.get(field.getFullyQualifiedName())));
                return taxonomy_code_value2;
            }
        }
        return field.getValueAsString().substring(Constants.ONE, field.getValueAsString().length() - 1);
    }


    /*
    con esta funcion se obtiene el codigo de taxonomia de ese codigo

    el pdf_code es el codigo del combo que esta seleccionado ( 904 )
    que se correspondera con una etiqueta ( Middle East )


    hay que hacer un bucle con todas las etiquetas del combo para obtener
    la etiqueta que se corresponde con el pdf_code seleccionado

    con el pdf_code y la etiqueta se llama a findTaxonomyCode que obtiene
    el taxonomy code
    */
    private String convertPdfCodeToText(final String pdf_code, final PDField field, final Integer atributeTaxonomyCode) {
        final PDComboBox pdComboBox = (PDComboBox) field;
        final Map<String, String> CodeDispla = new HashMap<String, String>();
        final AtomicInteger i = new AtomicInteger(-1);
        for (final String code : pdComboBox.getOptionsExportValues()) {
            CodeDispla.put(code, pdComboBox.getOptionsDisplayValues().get(i.incrementAndGet()));
        }
        try {
            return String.valueOf(CodeDispla.get(pdf_code));
        } catch (final AopInvocationException e) {
            return pdf_code;
        }
    }

    // esta hace lo inverso de la funcion convertPdfCodeToText
    private String convertPdfTextToCode(final String pdf_code, final PDField field, final Integer atributeTaxonomyCode) {
        final PDComboBox pdComboBox = (PDComboBox) field;
        final Map<String, String> CodeDispla = new HashMap<String, String>();
        final AtomicInteger i = new AtomicInteger(-1);
        for (final String code : pdComboBox.getOptionsExportValues()) {
            CodeDispla.put(pdComboBox.getOptionsDisplayValues().get(i.incrementAndGet()), code);
        }
        try {
            return String.valueOf(CodeDispla.get(pdf_code));
        } catch (final AopInvocationException e) {
            return pdf_code;
        }
    }

    public Map mountOcurrences(
            final Map<String, String> codeValueFormu,
            final PersonalDetails personaldetails,
            final Integer idContext,
            final EccairsAuthUser user,
            final Integer responsibleEntityId,
            final Integer reportingEntityId,
            final String pdfType,
            final String filename,
            final String taxonomyVersion,
            final TaxNode structure,
            final boolean isPublic
    ) {

        final Map json1 = new HashMap<>();
        final Map json2 = new HashMap<>();
        final Map json4 = new HashMap<>();
        final Map json5 = new HashMap<>();
        final Map json6 = new HashMap<>();
        final Map json97 = new HashMap<>();

        json1.put("type", Constants.JSON_REPORT_TYPE_OC);
        json1.put("reportingEntityId", reportingEntityId);
        json1.put("status", "DRAFT");
        json1.put("responsibleEntityId", responsibleEntityId);

        if (isPublic) {
            json2.put("firstName", personaldetails.getFirstName());
            json2.put("lastName", personaldetails.getLastName());
            json2.put("username", personaldetails.getUsername());
            json2.put("telephone", personaldetails.getTelephone());
            json2.put("email", personaldetails.getEmail());
            json1.put("personalDetails", json2);
        } else {
            json2.put("firstName", user.getUser().getFirstName());
            json2.put("lastName", user.getUser().getLastName());
            json2.put("username", user.getUser().getUsername());
            json2.put("telephone", user.getUser().getPhone());
            json2.put("email", user.getUser().getEmail());
            json1.put("personalDetails", json2);
        }

        json5.put("id", 1);
        json5.put("name", "name");
        json5.put("code", "code");

        json6.put("id", 1);
        json6.put("name", "name");

//        json4.put("id", 1);
        //json4.put("username", this.getUserActive(user));
//        json4.put("language", json5);
//        json4.put("authority", json6);
        json1.put("eccairsUser", json4);

//        json97.put(Constants.TAXONOMY_VERSION, taxonomyVersion);
        json97.put(Constants.TAXONOMY_ROOT, this.mountJson(structure, Constants.TAXONOMY_ROOT));
        json1.put(Constants.TAXONOMY_CODE, json97);
        return json1;
    }

    private Map mountJson(final TaxNode structure, final String id) {
        final Map json1 = new HashMap<>();
        final Map json2 = new HashMap<>();
        final Map json3 = new HashMap<>();

        final Map<String, String> taxIds = Constants.geTaxIds();
        json1.put(Constants.JSON_PDF_ID, taxIds.get(id));

        for (final Map.Entry<String, String> m : structure.attr.entrySet()) {
            if (m.getValue() != "") {
                json2.put(m.getKey(), m.getValue());
            }
        }
        json1.put(Constants.JSON_PDF_ATTRIBUTES, json2);

        for (final Map.Entry<String, TaxNode> m : structure.childs.entrySet()) {
            final Map json4 = this.mountJson(m.getValue(), m.getKey());
            json3.put(m.getKey(), json4);
        }
        if (json3.size() != 0) {
            json1.put(Constants.JSON_PDF_ENTITIES, json3);
        }
        return json1;
    }

    private Map<String, String> getMapCodeValuesIDInverse(final String pdfType) {
        Integer pdft = Integer.parseInt(pdfType);
        final Map<String, String> codeDValuesIDn2 = new HashMap<String, String>();
        final List<AttributeModel> att = this.attributeRepository.findAll();
        final HashMap atthash = new HashMap();
        for (final AttributeModel am : att) {
            atthash.put(am.getId(), am.getTaxonomy_code());
        }
        final List<PdfFieldConfModel> pfc = this.pdfFieldConfRepository.findAll();
        for (final PdfFieldConfModel reg : pfc) {
            if (reg.getPdf_id().equals(pdft)) {
                final Integer tax = (Integer) atthash.get(reg.getAttribute_id());
                codeDValuesIDn2.put(tax.toString(), reg.getName());
            }
        }
        return codeDValuesIDn2;
    }
}
