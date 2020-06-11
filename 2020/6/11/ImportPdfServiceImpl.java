package org.easa.eccairs.importpdf.core.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.easa.eccairs.auth.client.EccairsAuthUser;
import org.easa.eccairs.importpdf.core.dto.PersonalDetails;
import org.easa.eccairs.importpdf.core.exception.BadExtensionException;
import org.easa.eccairs.importpdf.core.exception.BadExtensionsException;
import org.easa.eccairs.importpdf.core.exception.BadPersonalDetailsException;
import org.easa.eccairs.importpdf.core.exception.BadRequestException;
import org.easa.eccairs.importpdf.core.exception.BeginuploadException;
import org.easa.eccairs.importpdf.core.exception.DownloadException;
import org.easa.eccairs.importpdf.core.exception.FileEmptyException;
import org.easa.eccairs.importpdf.core.exception.GenericException;
import org.easa.eccairs.importpdf.core.exception.UploadFileException;
import org.easa.eccairs.importpdf.core.provider.OcurrencesProvider;
import org.easa.eccairs.importpdf.core.service.ImportPdfService;
import org.easa.eccairs.importpdf.core.service.PdfParserService;
import org.easa.eccairs.importpdf.core.service.base.ImportPdfServiceBase;
import org.easa.eccairs.importpdf.data.model.PdfModel;
import org.easa.eccairs.importpdf.data.repository.OtherFileRepository;
import org.easa.eccairs.importpdf.data.repository.PdfRepository;
import org.easa.eccairs.importpdf.util.Constants;
import org.easa.eccairs.importpdf.util.TaxNode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ImportPdfServiceImpl extends ImportPdfServiceBase implements ImportPdfService {

    private final PdfParserService pdfParserService;

    private final OcurrencesProvider ocurrencesProvider;

    public ImportPdfServiceImpl(
            PdfRepository pdfRepository,
            OtherFileRepository otherFilesRepository,
            PdfParserService pdfParserService,
            OcurrencesProvider ocurrencesProvider
    ) {
        super(pdfRepository, otherFilesRepository);
        this.pdfParserService = pdfParserService;
        this.ocurrencesProvider = ocurrencesProvider;
    }

    @Override
    public Integer beginUploadFile() {
        final PdfModel pdfrepo = PdfModel.builder().build();
        pdfrepo.setModificationUser(null);
        pdfrepo.setCreationUser(null);
        this.pdfRepository.save(pdfrepo);
        if (Objects.isNull(pdfrepo.getId())) {
            throw new BeginuploadException();
        }
        return pdfrepo.getId();
    }

    @Override
    public void downloadPDFResource(
            final HttpServletResponse response,
            final String fileName
    ) {
        final File file;
        try {
            file = ResourceUtils.getFile("classpath:downloadfile/" + fileName);
        } catch (final FileNotFoundException e) {
            throw new BadRequestException("");
        }
        if (file.exists()) {
            String mimeType = URLConnection.guessContentTypeFromName(file.getName());
            if (mimeType == null) {
                mimeType = Constants.APPLICATION_OCTECT_STREAM;
            }
            response.setContentType(mimeType);
            response.setHeader(Constants.CONTENT_DISPOSITION, Constants.INLINE_FILE_NAME + file.getName() + "\"");
            response.setContentLength((int) file.length());
            try {
                final InputStream inputStream = new BufferedInputStream(new FileInputStream(file));
                FileCopyUtils.copy(inputStream, response.getOutputStream());
            } catch (final FileNotFoundException e) {
                throw new DownloadException("copy :" + e.getMessage());
            } catch (final IOException e) {
                throw new DownloadException("inputStream : " + e.getMessage());
            }
        } else {
            throw new DownloadException("file : " + Constants.NAME_FILE_EXITS);
        }
    }

    @Override
    public void uploadFile(MultipartFile uploadedFile, Integer idContext) {
        if (uploadedFile.isEmpty()) {
            throw new BadRequestException("");
        } else if (!this.getExtension(uploadedFile.getOriginalFilename()).toUpperCase().equals(Constants.PDF)) {
            throw new BadExtensionException();
        }
        final String filename = uploadedFile.getOriginalFilename();
        final Optional<PdfModel> optional = this.pdfRepository.findById(idContext);
        final PdfModel pdfrepo = optional.get();
        try {
            final byte[] bytes = uploadedFile.getBytes();
            final Blob blob = new javax.sql.rowset.serial.SerialBlob(bytes);
            pdfrepo.setModificationUser(null);
            pdfrepo.setCreationUser(null);
            pdfrepo.setCreationDate(new Date());
            pdfrepo.setFile(blob);
            pdfrepo.setName_file(filename);
            this.pdfRepository.save(pdfrepo);
        } catch (final IOException | SQLException e) {
            throw new UploadFileException(e.getMessage());
        }
    }

    @Override
    public void deleteFile(
            final Integer id
    ) {
        if (Objects.isNull(id)) {
            throw new FileEmptyException();
        }
        this.otherFilesRepository
                .findById(id)
                .ifPresent(otherFilesRepository::delete);
    }

    @Override
    public Map<String, String> uploadFiles(
            final MultipartFile[] uploadedFiles,
            final int idContext
    ) {
        if (!this.extensionMultiFileOK(uploadedFiles)) {
            throw new BadExtensionsException();
        }

        final String uploadedFileName = Arrays
                .stream(uploadedFiles)
                .map(MultipartFile::getOriginalFilename)
                .filter(x -> !StringUtils.isEmpty(x))
                .collect(Collectors.joining(" , "));

        if (StringUtils.isEmpty(uploadedFileName)) {
            throw new FileEmptyException();
        }
        final Map<String, String> listFileMultiUploadedFiles = this.saveMultiUploadedFiles(
                Arrays.asList(uploadedFiles),
                idContext
        );
        this.nameFileExits(listFileMultiUploadedFiles);
        return listFileMultiUploadedFiles;
    }

    @Override
    public boolean deleteSmartFile(
            Integer idContext
    ) {
        final Optional<PdfModel> optional = this.pdfRepository.findById(idContext);
        if (optional.isPresent()) {
            final PdfModel pdfrepo = optional.get();
            pdfrepo.setFile(null);
            pdfrepo.setName_file(null);
            pdfrepo.setCreationUser(null);
            pdfrepo.setCreationDate(null);
            this.pdfRepository.save(pdfrepo);
            return true;
        } else {
            throw new BadRequestException(Constants.ID_CONTEXT_NOT_FOUND);
        }
    }

    @Override
    public void deleteFiles(
            MultipartFile[] uploadedFiles,
            Integer idContext
    ) {
        for (final MultipartFile file : uploadedFiles) {
            final Integer id = this.otherFilesRepository.findOtherFileRepositoryByNameAndIdcontext(
                    idContext,
                    file.getOriginalFilename()
            );
            this.deleteFile(id);
        }
    }

    public ResponseEntity<Object> submitPdf(
            final PersonalDetails personaldetails,
            final EccairsAuthUser user,
            final int idContext,
            final int responsibleEntityId,
            final int reportingEntityId,
            final boolean isPublic
    ) {
        if (!isPublic) {
            if (Objects.isNull(user)) {
                throw new BadRequestException(Constants.USER_NULL);
            }
        }
        if (!pdfParserService.personalDetailsOK(personaldetails)) {
            throw new BadPersonalDetailsException();
        }
        Map<String, String> codeValueFormu = null;
        final Optional<PdfModel> optional = this.pdfRepository.findById(idContext);
        PdfModel pdfrepo = null;
        try {
            if (optional.isPresent()) {
                pdfrepo = optional.get();
                pdfrepo.setSubmit_do(true);
                this.pdfRepository.save(pdfrepo);
                this.pdfParserService.readPdf(pdfrepo);
            }
            final String filename = pdfrepo.getName_file();
            final String pdfType = this.pdfParserService.getPdfType(filename);
            if (null == pdfType) {
                throw new BadRequestException(Constants.NOT_OF_6_PDF_TYPES);
            }
            codeValueFormu = this.pdfParserService.getMapCodeValueIDForm(pdfrepo.getName_file(), pdfType);
            final HashMap<String, String> res = new HashMap<String, String>();
//            this.sendMail(personaldetails, user);

            final Map<String, String> hierarchy = this.pdfParserService.getMapCodeHierarchy(pdfType);
            final TaxNode structure = this.pdfParserService.getStructure(codeValueFormu, hierarchy, Constants.JSON_ROOT, pdfType);


            Map map = this.pdfParserService.mountOcurrences(
                    codeValueFormu,
                    personaldetails,
                    idContext,
                    user,
                    responsibleEntityId,
                    reportingEntityId,
                    pdfType,
                    filename,
                    "1",
                    structure,
                    isPublic);


            return this.ocurrencesProvider.getCreateOcurrencesPublic(map);
        } catch (final BadRequestException e) {
            throw new BadRequestException(e.getMessage());
        } catch (final Exception e) {
            throw new GenericException(e.getMessage());
        }
    }
}
