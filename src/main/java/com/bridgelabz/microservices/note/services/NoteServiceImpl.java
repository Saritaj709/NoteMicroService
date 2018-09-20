package com.bridgelabz.microservices.note.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.validator.routines.UrlValidator;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.bridgelabz.microservices.note.exception.DateException;
import com.bridgelabz.microservices.note.exception.LabelAdditionException;
import com.bridgelabz.microservices.note.exception.LabelNotFoundException;
import com.bridgelabz.microservices.note.exception.MalFormedException;
import com.bridgelabz.microservices.note.exception.NoSuchLabelException;
import com.bridgelabz.microservices.note.exception.NoteArchievedException;
import com.bridgelabz.microservices.note.exception.NoteCreationException;
import com.bridgelabz.microservices.note.exception.NoteNotFoundException;
import com.bridgelabz.microservices.note.exception.NotePinnedException;
import com.bridgelabz.microservices.note.exception.NoteTrashedException;
import com.bridgelabz.microservices.note.exception.NoteUnArchievedException;
import com.bridgelabz.microservices.note.exception.NoteUnPinnedException;
import com.bridgelabz.microservices.note.exception.NullValueException;
import com.bridgelabz.microservices.note.exception.UnAuthorizedException;
import com.bridgelabz.microservices.note.exception.UntrashedException;
import com.bridgelabz.microservices.note.exception.UrlAdditionException;
import com.bridgelabz.microservices.note.model.CreateDTO;
import com.bridgelabz.microservices.note.model.Note;
import com.bridgelabz.microservices.note.model.UpdateDTO;
import com.bridgelabz.microservices.note.model.UrlMetaData;
import com.bridgelabz.microservices.note.model.ViewNoteDTO;
import com.bridgelabz.microservices.note.repository.ElasticRepositoryForLabel;
import com.bridgelabz.microservices.note.repository.ElasticRepositoryForNote;
import com.bridgelabz.microservices.note.repository.NoteRepository;
import com.bridgelabz.microservices.note.utility.NoteUtility;
import com.bridgelabz.microservices.note.model.Label;

@Service
public class NoteServiceImpl implements NoteService {

	@Autowired
	private NoteRepository noteRepository;

	@Autowired
	private ModelMapper modelMapper;

	@Autowired
	private ElasticRepositoryForLabel labelElasticRepository;

	@Autowired
	private ElasticRepositoryForNote noteElasticRepository;

	@Autowired
	private LabelService labelService;

	@Autowired
	private Environment environment;

	@Autowired
	private ContentScrapService scrap;

	@Autowired
	private AwsS3Service awsS3Service;

	/**
	 * 
	 * @param token
	 * @param create
	 * @return Note
	 * @throws NoteNotFoundException
	 * @throws NoteCreationException
	 * @throws UnAuthorizedException
	 * @throws DateException
	 * @throws LabelNotFoundException
	 * @throws NullValueException
	 * @throws MalFormedException
	 */
	@Override
	public ViewNoteDTO createNote(String userId, CreateDTO createDto)
			throws NoteNotFoundException, NoteCreationException, UnAuthorizedException, DateException,
			LabelNotFoundException, NullValueException, MalFormedException {

		NoteUtility.validateNoteCreation(createDto);

		Note note = modelMapper.map(createDto, Note.class);

		if (createDto.getColor().equals(null) || createDto.getColor().length() == 0
				|| createDto.getColor().trim().length() == 0) {
			note.setColor(environment.getProperty("Color"));
		}

		if (createDto.getReminder().before(new Date())) {
			throw new DateException(environment.getProperty("DateException"));

		}

		note.setUserId(userId);
		note.setCreatedAt(new Date());
		note.setLastModifiedAt(new Date());

		for (int i = 0; i < createDto.getLabels().size(); i++) {

			List<Label> labels = labelElasticRepository
					.findByLabelNameAndUserId(createDto.getLabels().get(i).getLabelName(), userId);

			if (labels.isEmpty()) {

				labelService.createLabel(userId, createDto.getLabels().get(i).getLabelName());

				List<Label> labels1 = labelElasticRepository
						.findByLabelNameAndUserId(createDto.getLabels().get(i).getLabelName(), userId);

				note.setLabels(labels1);

			}
		}

		String[] contents = createDto.getDescription().split(" ");
		if (contents.length > 0) {

			List<UrlMetaData> metaData = scrap.addSplitContent(createDto.getDescription());
			note.setMetaData(metaData);
		}
		List<String> descriptionList = new ArrayList<>();
		descriptionList.add(createDto.getDescription());
		note.setDescription(descriptionList);

		List<String> imageList = new ArrayList<>();
		imageList.add(environment.getProperty("sourceFolder") + createDto.getImage());

		awsS3Service.uploadFile(environment.getProperty("bucketName"), environment.getProperty("folderNameForNote"),
				createDto.getImage());

		note.setImage(imageList);
		noteRepository.save(note);

		noteElasticRepository.save(note);

		ViewNoteDTO viewNoteDto = modelMapper.map(note, ViewNoteDTO.class);

		return viewNoteDto;

	}

	/**
	 * @param userId
	 * @param noteId
	 * @param url
	 * @throws MalFormedException
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws UrlAdditionException
	 */
	@Override
	public void addContentToNote(String userId, String noteId, String url)
			throws MalFormedException, NoteNotFoundException, UnAuthorizedException, UrlAdditionException {

		Optional<Note> optionalNote = noteElasticRepository.findByNoteId(noteId);
		if (!optionalNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = optionalNote.get();
		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		List<String> descriptionList = new ArrayList<>();

		String[] contents = url.split(" ");

		descriptionList = note.getDescription();
		// if (descriptionList != null) {

		for (int i = 0; i < descriptionList.size(); i++) {

			// if(contents.length>0) {

			for (int j = 0; j < contents.length; j++) {
				UrlValidator validator = new UrlValidator();
				if (validator.isValid(contents[j]) && descriptionList.contains(contents[j])) {
					// if (descriptionList.contains(contents[j])) {
					throw new UrlAdditionException(environment.getProperty("UrlAdditionException"));
				}
			}
		}
		// }
		// }
		// }
		descriptionList.add(url);
		List<String> desList = descriptionList;
		note.setDescription(desList);

		List<UrlMetaData> listMetaData = new ArrayList<>();

		listMetaData = note.getMetaData();

		if (listMetaData != null) {

			if (contents.length > 0) {
				List<UrlMetaData> metaData = scrap.addSplitContent(url);

				listMetaData.addAll(metaData);
				note.setMetaData(listMetaData);

			}
			if (contents.length == 0) {
				List<UrlMetaData> data = scrap.addContent(url);
				listMetaData.addAll(data);
				note.setMetaData(listMetaData);
			}
		} else {

			if (contents.length > 0) {
				List<UrlMetaData> metaData = scrap.addSplitContent(url);

				note.setMetaData(metaData);
			}
			if (contents.length == 0) {
				List<UrlMetaData> data = scrap.addContent(url);
				note.setMetaData(data);
			}
		}
		noteRepository.save(note);
		noteElasticRepository.save(note);
	}

	/**
	 * 
	 * @param token
	 * @param labelId
	 * @param noteId
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws NoteTrashedException
	 * @throws LabelAdditionException
	 */
	@Override
	public void addLabel(String userId, String labelId, String noteId)
			throws NoteNotFoundException, UnAuthorizedException, NoteTrashedException, LabelAdditionException {

		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		// List<Label> labels = labelRepository.findByLabelIdAndUserId(labelId,userId);

		List<Label> labels = labelElasticRepository.findByLabelIdAndUserId(labelId, userId);

		if (labels.isEmpty()) {
			throw new NoSuchLabelException(environment.getProperty("NoSuchLabelException"));
		}

		List<Label> tempList = new LinkedList<>();

		tempList = note.getLabels();

		if (tempList != null) {

			for (int i = 0; i < tempList.size(); i++) {

				if (tempList.get(i).getLabelId().equals(labelId)) {
					throw new LabelAdditionException(environment.getProperty("LabelAdditionException"));
				}
			}
			tempList.addAll(labels);

			note.setLabels(tempList);
		}

		else {
			note.setLabels(labels);
		}
		noteRepository.save(note);

		noteElasticRepository.save(note);

	}

	/**
	 * 
	 * @param userId
	 * @param noteId
	 * @param labelId
	 * @throws LabelNotFoundException
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 */
	@Override
	public void removeLabelFromNote(String userId, String noteId, String labelId)
			throws LabelNotFoundException, NoteNotFoundException, UnAuthorizedException {

		// Optional<Label> optionalLabel = labelRepository.findByLabelId(labelId);

		Optional<Label> optionalLabel = labelElasticRepository.findByLabelId(labelId);
		if (!optionalLabel.isPresent()) {
			throw new LabelNotFoundException(environment.getProperty("LabelNotFoundException"));
		}

		Label label = optionalLabel.get();

		// Optional<Note> optionalNote = noteRepository.findByNoteId(noteId);
		Optional<Note> optionalNote = noteElasticRepository.findByNoteId(noteId);
		if (!optionalNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = optionalNote.get();

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		if (!label.getUserId().equals(userId)) {
			throw new UnAuthorizedException("this particular label is not authorized for given user");
		}

		for (int i = 0; i < note.getLabels().size(); i++) {
			if (note.getLabels().get(i).getLabelId().equals(labelId)) {
				note.getLabels().remove(i);
				noteRepository.save(note);
				noteElasticRepository.save(note);
			}
		}
	}

	/**
	 * 
	 * @param token
	 * @param update
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws NoteTrashedException
	 */
	@Override
	public void updateNote(String userId, UpdateDTO updateDto)
			throws NoteNotFoundException, UnAuthorizedException, NoteTrashedException {

		Optional<Note> checkNote = noteRepository.findById(updateDto.getNoteId());

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		Note note1 = modelMapper.map(updateDto, Note.class);

		note1.setCreatedAt(note.getCreatedAt());
		note1.setLastModifiedAt(new Date());
		note1.setReminder(note.getReminder());
		note1.setColor(note.getColor());
		note1.setUserId(note.getUserId());

		noteRepository.save(note1);
		noteElasticRepository.save(note1);
	}

	/**
	 * 
	 * @return List of Trashed Notes
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> viewTrashed(String userId) throws NullValueException {

		List<Note> noteList = noteElasticRepository.findAllByUserIdAndTrashed(userId, true);

		if (noteList == null) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		return noteList.stream().map(filterNote -> modelMapper.map(filterNote, ViewNoteDTO.class))
				.collect(Collectors.toList());
	}

	/**
	 * 
	 * @return List of Notes of A Particular user
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> readAllNotes() throws NullValueException {

		// List<Note> noteList = noteRepository.findAll();
		List<Note> noteList = noteRepository.findAll();

		if (noteList == null) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		List<ViewNoteDTO> viewList = new LinkedList<>();

		for (int index = 0; index < noteList.size(); index++) {

			if (!noteList.get(index).isTrashed()) {

				ViewNoteDTO viewDto = modelMapper.map(noteList.get(index), ViewNoteDTO.class);
				viewList.add(viewDto);
			}
		}
		return viewList;
	}

	/**
	 * 
	 * @param userId
	 * @return List of ViewNoteDTO
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> readUserNotes(String userId) throws NullValueException {

		List<Note> noteList = noteRepository.findAllByUserIdAndTrashed(userId, false);
		// List<Note> noteList=noteElasticRepository.findAllByUserIdAndTrashed(userId,
		// false);
		if (noteList.isEmpty()) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		List<ViewNoteDTO> pin = noteList.stream().filter(noteStream -> noteStream.isPin())
				.map(filterNote -> modelMapper.map(filterNote, ViewNoteDTO.class)).collect(Collectors.toList());
		List<ViewNoteDTO> unPin = noteList.stream().filter(noteStream -> !noteStream.isPin())
				.map(filterNote -> modelMapper.map(filterNote, ViewNoteDTO.class)).collect(Collectors.toList());
		return Stream.concat(pin.stream(), unPin.stream()).collect(Collectors.toList());
	}

	/**
	 * 
	 * @param token
	 * @param noteId
	 * @return List of ViewNoteDTO
	 * @throws UnAuthorizedException
	 * @throws NoteNotFoundException
	 * @throws NoteTrashedException
	 */
	@Override
	public ViewNoteDTO findNoteById(String userId, String noteId)
			throws UnAuthorizedException, NoteNotFoundException, NoteTrashedException {

		// Optional<Note> checkNote = noteRepository.findById(noteId);

		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		ViewNoteDTO viewDto = modelMapper.map(note, ViewNoteDTO.class);

		return viewDto;
	}

	@Override
	public void deleteNoteForever(String userId, String noteId)
			throws NoteNotFoundException, UnAuthorizedException, UntrashedException, NoteTrashedException {

		// Optional<Note> checkNote = noteRepository.findByNoteId(noteId);

		Optional<Note> checkNote = noteElasticRepository.findByNoteId(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (!note.isTrashed()) {
			throw new UntrashedException(environment.getProperty("UntrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		/*List<String> imageList = new ArrayList<>();
		imageList = note.getImage();

		for (int i = 0; i < imageList.size(); i++) {
			String[] images = imageList.get(i).split("/");
			if (images.length > 0) {
				for (int j = 0; j < images.length; j++) {
					if (images[j].endsWith(".png") || images[j].endsWith(".jpg") || images[j].endsWith(".jpeg")
							|| images[j].endsWith("svg") || images[j].endsWith("svg")) {

						awsS3Service.deleteImagesFromNote(images[j]);
					}
				}
			}
		}*/
		noteRepository.deleteByNoteId(noteId);
		noteElasticRepository.deleteByNoteId(noteId);
	}

	/**
	 * 
	 * @param userId
	 * @param color
	 * @param noteId
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws NoteTrashedException
	 */
	@Override
	public void addColor(String userId, String color, String noteId)
			throws NoteNotFoundException, UnAuthorizedException, NoteTrashedException {

		// Optional<Note> checkNote = noteRepository.findById(noteId);

		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		note.setColor(color);
		noteRepository.save(note);
		noteElasticRepository.save(note);

	}

	/**
	 * 
	 * @param token
	 * @param date
	 * @param noteId
	 * @return boolean
	 * @throws UnAuthorizedException
	 * @throws NoteNotFoundException
	 * @throws NoteTrashedException
	 * @throws DateException
	 */
	@Override
	public void addReminder(String userId, Date date, String noteId)
			throws UnAuthorizedException, NoteNotFoundException, NoteTrashedException, DateException {

		if (date.before(new Date())) {
			throw new DateException(environment.getProperty("DateException"));
		}

		// Optional<Note> checkNote = noteRepository.findById(noteId);

		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty(" UnAuthorizedException"));
		}

		note.setReminder(date);

		noteRepository.save(note);
		noteElasticRepository.save(note);

	}

	/**
	 * 
	 * @param token
	 * @param noteId
	 * @throws NullValueException
	 * @throws UnAuthorizedException
	 * @throws NoteNotFoundException
	 * @throws NoteTrashedException
	 */
	@Override
	public void deleteReminder(String userId, String noteId)
			throws NullValueException, UnAuthorizedException, NoteNotFoundException, NoteTrashedException {

		// Optional<Note> checkNote = noteRepository.findById(noteId);
		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		note.setReminder(null);
		noteRepository.save(note);
		noteElasticRepository.save(note);
	}

	/**
	 * 
	 * @param token
	 * @param noteId
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws NoteArchievedException
	 * @throws NoteTrashedException
	 * @throws NoteUnArchievedException
	 */
	@Override
	public void archieveOrUnArchieveNote(String userId, String noteId, boolean choice) throws NoteNotFoundException,
			UnAuthorizedException, NoteArchievedException, NoteTrashedException, NoteUnArchievedException {

		// Optional<Note> checkNote = noteRepository.findById(noteId);

		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		note.setArchieve(choice);

		noteRepository.save(note);
		noteElasticRepository.save(note);
	}

	/**
	 * 
	 * @return List of Archived Notes
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> viewArchieved(String userId) throws NullValueException {

		// List<Note> noteList = noteRepository.findAll();
		List<Note> noteList = noteElasticRepository.findAllByUserIdAndTrashed(userId, false);

		if (noteList == null) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		return noteList.stream().filter(noteStream -> noteStream.isArchieve())
				.map(filterNote -> modelMapper.map(filterNote, ViewNoteDTO.class)).collect(Collectors.toList());
	}

	/**
	 * 
	 * @param token
	 * @param noteId
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws NotePinnedException
	 * @throws NoteTrashedException
	 * @throws NoteUnPinnedException
	 */
	@Override
	public void pinOrUnpinNote(String userId, String noteId, boolean choice) throws NoteNotFoundException,
			UnAuthorizedException, NotePinnedException, NoteTrashedException, NoteUnPinnedException {

		// Optional<Note> checkNote = noteRepository.findById(noteId);
		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		note.setPin(choice);

		noteRepository.save(note);
		noteElasticRepository.save(note);
	}

	/**
	 * 
	 * @return List of Pinned Notes
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> viewPinned(String userId) throws NullValueException {

		// List<Note> notes = noteRepository.findAll();
		List<Note> notes = noteElasticRepository.findAllByUserIdAndTrashed(userId, false);
		// List<Note> notes = noteElasticRepository.findAllByUserId(userId);

		if (notes == null) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		return notes.stream().filter(noteStream -> noteStream.isPin())
				.map(filterNote -> modelMapper.map(filterNote, ViewNoteDTO.class)).collect(Collectors.toList());
	}

	/**
	 * 
	 * @param token
	 * @param noteId
	 * @param choice
	 * @throws NoteNotFoundException
	 * @throws UnAuthorizedException
	 * @throws UntrashedException
	 * @throws NoteTrashedException
	 */
	@Override
	public void deleteOrRestoreNote(String userId, String noteId, boolean choice)
			throws NoteNotFoundException, UnAuthorizedException, UntrashedException, NoteTrashedException {

		Optional<Note> checkNote = noteRepository.findByNoteId(noteId);
		// Optional<Note> checkNote = noteElasticRepository.findByNoteId(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		note.setTrashed(choice);

		noteRepository.save(note);
		noteElasticRepository.save(note);
	}

	/**
	 * @param userId
	 * @param order
	 * @return noteDTO sorted by title
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> viewNotesBySortedTitle(String userId, String order) throws NullValueException {

		List<Note> noteList = noteElasticRepository.findAllByUserIdAndTrashed(userId, false);
		if (noteList.isEmpty()) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		if (order.equals("asc")) {
			return noteList.stream().sorted(Comparator.comparing(Note::getTitle))
					.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());
		}

		if (order.equals("desc")) {

			return noteList.stream().sorted(Comparator.comparing(Note::getTitle).reversed())
					.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());
		}
		return null;
	}

	/**
	 * @param userId
	 * @param order
	 * @return noteDTO sorted by date
	 * @throws NullValueException
	 */
	@Override
	public List<ViewNoteDTO> viewNotesBySortedDate(String userId, String order, String choice)
			throws NullValueException {

		List<Note> noteList = noteElasticRepository.findAllByUserIdAndTrashed(userId, false);
		if (noteList.isEmpty()) {
			throw new NullValueException(environment.getProperty("NullValueException"));
		}

		if (choice == null && order == null) {
			return noteList.stream().sorted(Comparator.comparing(Note::getCreatedAt).reversed())
					.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());

		}

		if (choice.equals("date")) {

			if (order == null || order.matches(".*")) {
				return noteList.stream().sorted(Comparator.comparing(Note::getCreatedAt).reversed())
						.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());
			}

			if (order.equalsIgnoreCase("asc")) {
				return noteList.stream().sorted(Comparator.comparing(Note::getCreatedAt))
						.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());
			}

		}

		if (choice.equals("title")) {

			if (order == null || order.matches(".*")) {
				return noteList.stream().sorted(Comparator.comparing(Note::getTitle))
						.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());

			}

			if (order.equals("desc")) {

				return noteList.stream().sorted(Comparator.comparing(Note::getTitle).reversed())
						.map(SortedNote -> modelMapper.map(SortedNote, ViewNoteDTO.class)).collect(Collectors.toList());
			}

		}

		return null;
	}

	@Override
	public String addImageToNote(String userId, String noteId, String image)
			throws NoteNotFoundException, NoteTrashedException, UnAuthorizedException {
		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		List<String> tempList = note.getImage();

		tempList.add(environment.getProperty("imageLinkForNote") + image);

		note.setImage(tempList);

		awsS3Service.uploadFile(environment.getProperty("bucketName"), environment.getProperty("folderNameForNote"),
				image);

		noteRepository.save(note);

		noteElasticRepository.save(note);
		return image;
	}

	@Override
	public String removeImageFromNote(String userId, String noteId, String image)
			throws NoteNotFoundException, NoteTrashedException, UnAuthorizedException, NullValueException {

		Optional<Note> checkNote = noteElasticRepository.findById(noteId);

		if (!checkNote.isPresent()) {
			throw new NoteNotFoundException(environment.getProperty("NoteNotFoundException"));
		}

		Note note = checkNote.get();

		if (note.isTrashed()) {
			throw new NoteTrashedException(environment.getProperty("NoteTrashedException"));
		}

		if (!note.getUserId().equals(userId)) {
			throw new UnAuthorizedException(environment.getProperty("UnAuthorizedException"));
		}

		List<String> tempList = note.getImage();

		for (int i = 0; i < tempList.size(); i++) {

			if (!tempList.contains(environment.getProperty("imageLinkForNote") + image)) {
				throw new NullValueException(environment.getProperty("NullValueException"));

			}
		}
		tempList.remove(environment.getProperty("imageLinkForNote") + image);
		note.setImage(tempList);

		awsS3Service.deleteImagesFromNote(image);
		noteRepository.save(note);

		noteElasticRepository.save(note);
		return image;
	}
}
